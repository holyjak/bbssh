(ns pod.epiccastle.bbssh.core
  "Basic connection, execution, shell and copying functionality."
  (:require [pod.epiccastle.bbssh.agent :as agent]
            [pod.epiccastle.bbssh.session :as session]
            [pod.epiccastle.bbssh.utils :as utils]
            [pod.epiccastle.bbssh.user-info :as user-info]
            [pod.epiccastle.bbssh.host-key-repository :as host-key-repository]
            [pod.epiccastle.bbssh.terminal :as terminal]
            [pod.epiccastle.bbssh.channel-exec :as channel-exec]
            [pod.epiccastle.bbssh.input-stream :as input-stream]
            [pod.epiccastle.bbssh.output-stream :as output-stream]
            [pod.epiccastle.bbssh.byte-array-output-stream :as byte-array-output-stream]
            [pod.epiccastle.bbssh.byte-array-input-stream :as byte-array-input-stream]
            [clojure.string :as string]))

(def ^:private special-config-var-names
  {"kex" ["kex"]
   "server-host-key" ["server_host_key"]
   "prefer-known-host-key-types" ["prefer_known_host_key_types"]
   "enable-server-sig-algs" ["enable_server_sig_algs"]
   "cipher" ["cipher.s2c" "cipher.c2s"]
   "cipher-s2c" ["cipher.s2c"]
   "cipher-c2s" ["cipher.c2s"]
   "mac" ["mac.s2c" "mac.c2s"]
   "mac-s2c" ["mac.s2c"]
   "mac-c2s" ["mac.c2s"]
   "compression" ["compression.s2c" "compression.c2s"]
   "compression-s2c" ["compression.s2c"]
   "compression-c2s" ["compression.c2s"]
   "lang" ["lang.s2c" "lang.c2s"]
   "lang-s2c" ["lang.s2c"]
   "lang-c2s" ["lang.c2s"]
   "dhgex-min" ["dhgex_min"]
   "dhgex-max" ["dhgex_max"]
   "dhgex-preferred" ["dhgex_preferred"]
   "compression-level" ["compression_level"]
   "client-pubkey" ["PubkeyAcceptedAlgorithms"]})

(defn- process-session-connection-options
  "process the ssh connection-options setting and setup session
  accordingly"
  [session options]
  (doseq [[k v] options]
    (let [nk (name k)
          config-var-names
          (get special-config-var-names nk [nk])]
      (if (ifn? v)
        ;; function value based config
        (doseq [n config-var-names]
          (prn 'get-config session n '=> (session/get-config session n))
          (session/set-config
           session n
           (v (session/get-config session n))))
        ;; string value based config
        (doseq [n config-var-names]
          (session/set-config session n v))))))

(defn- print-flush-ask-yes-no [s]
  (print (str s " "))
  (.flush *out*)
  (let [response (read-line)
        first-char (first response)]
    (boolean (#{\y \Y} first-char))))

(defn- make-default-user-info
  [{:keys [accept-host-key]}]
  (let [message (atom nil)]
    (user-info/new
     {:get-password
      (fn []
        (print (str "Enter " @message ": "))
        (.flush *out*)
        (if-let [password (terminal/raw-mode-readline)]
          (do
            (println)
            password)
          (do
            (println "^C")
            (System/exit 1))))

      :prompt-yes-no
      (fn [s]
        (let [host-key-missing?
              (and (.contains s "authenticity of host")
                   (.contains s "can't be established"))
              host-key-changed?
              (.contains s "IDENTIFICATION HAS CHANGED")
              ]
          (cond
            host-key-missing?
            (let [fingerprint (second (re-find #"fingerprint is (.+)." s))]
              (cond
                (#{:new "new"} accept-host-key)
                true

                (= true accept-host-key)
                true

                (= false accept-host-key)
                false

                (and (string? accept-host-key)
                     (= fingerprint
                        accept-host-key))
                true ;; fingerprint matches

                (string? accept-host-key)
                false ;; fingerprint does not match

                :else
                (print-flush-ask-yes-no s)))

            host-key-changed?
            (let [fingerprint (second (re-find #"The fingerprint for the .+ key sent by the remote host .+ is\n(.+).\n" s))]
              (cond
                (#{:new "new"} accept-host-key)
                false

                (= true accept-host-key)
                true

                (= false accept-host-key)
                false

                (and (string? accept-host-key)
                     (= fingerprint
                        accept-host-key))
                true ;; fingerprint matches

                (string? accept-host-key)
                false ;; fingerprint does not match

                :else
                (print-flush-ask-yes-no s)))

            :else
            (print-flush-ask-yes-no s))))

      :get-passphrase
      (fn []
        (print (str "Enter " @message ": "))
        (.flush *out*)
        (if-let [passphrase (terminal/raw-mode-readline)]
          (do
            (println)
            passphrase)
          (do
            (println "^C")
            (System/exit 1))))

      :prompt-passphrase
      (fn [s]
        (reset! message s)
        ;; true: continue to decrypt key. false: cancel key decrypt
        true)

      :prompt-password
      (fn [s]
        (reset! message s)
        ;; true: continue to connect. false: cancel authentication
        true)

      :show-message
      (fn [s]
        (println s))})))

(defn- make-default-host-key-repository []
  (host-key-repository/new
   {:check (fn [host key]
             (prn :check host key)
             :changed)
    :add (fn [host-key user-info]
           (prn :add host-key user-info)
           nil)
    :remove (fn
              ([host type]
               (prn :remove1 host type)
               nil)
              ([host type key]
               (prn :remove2 host type key)
               nil))
    :get-known-hosts-repository-id (fn []
                                     (prn :get-known-hosts-repository-id)
                                     "my-khr")
    :get-host-key (fn
                    ([]
                     (prn :get-host-key)
                     [])
                    ([host type]
                     (prn :get-host-key host type)
                     []))}))

(defn ssh
  "Start an SSH session. If connection is successful, returns the SSH
  session reference. If connection is unsuccessful, raises an
  exception. Requires a string for hostname.  Pass in an optional
  hashmap with the following keys:

  - `:port` The port to connect to. Defaults to 22.
  - `:username` The username to connect as. Defaults to the
    current username.
  - `:password` Pass in a string to use for password authentication.
  - `:identity` Pass in a file or filename of the private key to use
    in key authentication.
  - `:passphrase` Pass in a string to decrypt the private key with if
    needed.
  - `:private-key` Pass in a string (base64) or byte array of
    the private key to use for authentication.
  - `:public-key` Pass in a string (base64) or byte array of
    the public key associated with the private-key
  - `:agent-forwarding` If using an ssh-agent for authentication then
    turn on SSH agent authentication forwarding for this session.
    Default is `false`
  - `:strict-host-key-checking` Control strict host key checking.
    If set to `true`, bbssh will never add host keys to known host
    and will refuse to connect to hosts whose host key has changed.
    If set to `false`, bbssh will allow connection to hosts with
    unknown or changed keys. If the key is unknown it will add it to known
    hosts, but it will not change a key if it is present. A
    value of `:ask` will mean new host keys will be added to the known
    hosts, or a changed key modified, only after the user has confirmed
    this is what they wish to do via the terminal.
    Default is `:ask`
  - `:known-hosts` A string defining the path to the known_hosts file
    to use. It is set to ~/.ssh/known_hosts by default. Set to `false`
    to disable using a known hosts file.
  - `:accept-host-key` When `:strict-host-key-checking` is set to `:ask`
    this setting controls the adding of the key. If unset, or set to `nil`
    the default behavior of asking the user via the terminal applies.
    If `true` accept the host key
    if it is unknown or changed. If `false` reject the connection if the host key
    is unknown or changed. If set to a string, accept the host-key only if the
    key fingerprint matches the string. If set to `:new`, accept the key
    if it is unknown, but reject it if it has changed.
    Defaults to `nil`.
  - `:connection-options` A hashmap of lesser used connection options.
    See below for details.
  - `:no-connect` Set to true to prevent the connection from being
    initiated. Just returns the prepared session reference. You will then
    need to call `session/connect` on it to initiate the connection.
  - `:agent` The bbssh agent to use to construct the session. If none
    is supplied a new bbssh agent will be created. Note: this is not
    an ssh-agent. It is the root class that contains all the session
    instances and settings.
  - `:identity-repository` Use a custom identity-repository in the
    connection.
  - `:user-info` Use a custom user-info in the connection. Specifying
    this value will override `:accept-host-key` and change the behavior
    of password and passphrase prompts (your user-info functions will
    be called instead).
  - `:host-key-repository` Use a custom host-key-repository in the
    connection. Specifying this value will override `:known-hosts`
    setting (your host-key-repository functions will be called instead).

  The hashmap passed in `:connection-options` can have the following
  keys. Each key takes a string or a function as a configuration
  value.  If passed a string, that string becomes the set value. If
  passed a function this function should take a single string as an
  argument and return a string. It will be passed the existing
  configuration setting and should return the new configuration
  setting. These can be used to append or prepend extra values onto
  the default settings.

  `:kex`, `:server-host-key`, `:prefer-known-host-key-types`,
  `:enable-server-sig-algs`, `:cipher`, `:cipher-s2c`, `:cipher-c2s`,
  `:mac`, `:mac-s2c`, `:mac-c2s`, `:compression`, `:compression-s2c`,
  `:compression-c2s`, `:lang`, `:lang-s2c`, `:lang-c2s`, `:dhgex-min`,
  `:dhgex-max`, `:dhgex-preferred`, `:compression-level`,
  `:preferred-authentications`, `:client-pubkey`, `:check-ciphers`,
  `:check-macs`, `:check-kexes`, `:check-signatures`,
  `:fingerprint-hash`, `:max-auth-tries`
  "
  [hostname
   & [{:keys [agent port username password
              identity passphrase private-key public-key
              agent-forwarding strict-host-key-checking
              known-hosts accept-host-key connection-options
              no-connect identity-repository user-info
              host-key-repository]
       :or {port 22
            agent-forwarding false
            strict-host-key-checking :ask
            accept-host-key false
            connection-options {}}
       :as options}]]
  (let [username (or username (System/getProperty "user.name"))
        agent (or agent (agent/new))
        session (agent/get-session agent username hostname port)]
    (if (not= false known-hosts)
        (agent/set-known-hosts
         agent
         (or known-hosts
             (str (System/getProperty "user.home")
                  "/.ssh/known_hosts"))))
    (when password (session/set-password session password))
    (when identity
      (if passphrase
        (agent/add-identity agent identity passphrase)
        (agent/add-identity agent identity)))
    (when private-key
      (agent/add-identity
       agent
       (str "inline key for " username "@" hostname)
       (utils/opt-decode-base64 private-key)
       (utils/opt-decode-base64 (or public-key ""))
       (utils/opt-get-bytes (or passphrase ""))))
    (cond
      (#{:ask "ask"} strict-host-key-checking)
      (session/set-config session :strict-host-key-checking "ask")
      strict-host-key-checking
      (session/set-config session :strict-host-key-checking true)
      :else
      (session/set-config session :strict-host-key-checking false))
    (when connection-options
      (process-session-connection-options session connection-options))
    (when identity-repository
      (session/set-identity-repository session identity-repository))
    (session/set-user-info
     session
     (or user-info
         (make-default-user-info options)))
    (when host-key-repository
        (session/set-host-key-repository session host-key-repository))

    #_(session/set-host-key-repository session (make-default-host-key-repository))

    (when-not no-connect
      (session/connect session))
    session))

(defn wait
  "waits until a ssh exec remote process has finished executing and then
  returns the exit code. Optionally pass a timeout value in
  milliseconds. If the timeout is reached and the process has not
  finished then returns `nil`."
  [{:keys [channel]} & [timeout]]
  (let [status (channel-exec/get-exit-status channel)]
    (cond
      (<= 0 status)
      status

      (and timeout (<= timeout 0))
      nil

      timeout
      (let [deadline (+ (System/nanoTime) (* timeout 1000000))]
        (loop [remaining (- deadline (System/nanoTime))]
          (when (pos? remaining)
            (Thread/sleep (min (inc (/ remaining 1000000)) 100))
            (let [status (channel-exec/get-exit-status channel)]
              (if (<= 0 status)
                status
                (recur (- deadline (System/nanoTime))))))))

      :else ;; no timeout, block forever
      (loop []
        (Thread/sleep 100)
        (let [status (channel-exec/get-exit-status channel)]
          (if (<= 0 status)
            status
            (recur)))))))

(defn exec
  "Execute a `command` on the remote host over the ssh `session`.
  `options` should be a hashmap with the following keys:

  - `:agent-forwarding` If set to `true` enables ssh authentication agent
    for this command.
  - `:pty` If set to `true` allocate a pseudo terminal for this
    command execution.
  - `:in` Specify the data to be passed to stdin of the process. Can
    be `nil`, a string, a local byte array, a local InputStream instance (which
    will be .read from), a pod input-stream reference, or the keyword `:stream`.
    If the keyword `:stream` is specified, a PipedInputStream/PipedOutputStream
    instance pair will be created and the PipedOutputStream will be returned
    in the `:in` key of the result (for you to .write or clojure.java.io/copy
    to).
  - `:in-enc` If `:in` is a string then optionally set the input encoding with this. Defaults to \"utf-8\".
  - `:out` Specify how you want the output to be handled. Use keyword
    :string to
    store a string when the process is complete. Use keyword :bytes
    to store a byte array when the output is complete. Pass a local
    OutputStream instance (which will be used to .write to) or a pod
    output-stream reference (which will be used to output-stream/write
    to). If passed a value of `:stream` is passed or the value is
    ommitted, a PipedOutputStream/PipedInputStream instance pair
    is created and the PipedInputStream will be returned in the `:out`
    key of the result (for you to .read or clojure.java.io/copy from).
  - `:format` Specify the format the stdout and stderr should be returned
    as. `:stream` will return stream objects, `:bytes` will return
    byte arrays and `:string` will return strings. Default is `:string`
  - `:encoding` When `:format` is set to `:string` this option controls
    the encoding used to generate the string. Default is `\"utf-8\".

  "
  [session command
   & [{:keys [agent-forwarding pty
              in in-enc
              out out-enc
              err err-enc
              pipe-buffer-size
              ]
       :or {pipe-buffer-size 8192
            in-enc "utf-8"
            out-enc "utf-8"
            err-enc "utf-8"}
       :as options}]]
  (let [channel (session/open-channel session "exec")]
    (channel-exec/set-command channel command)
    (when pty
      (channel-exec/set-pty channel (boolean pty)))
    (when agent-forwarding
      (channel-exec/set-agent-forwarding channel (boolean agent-forwarding)))
    (let [
          in-stream
          (cond
            (nil? in)
            (do
              (->> (byte-array-input-stream/new "")
                   (channel-exec/set-input-stream channel))
              nil)

            (string? in)
            (let [in-stream (byte-array-input-stream/new in in-enc)]
              (channel-exec/set-input-stream channel in-stream)
              in-stream)

            (bytes? in)
            (let [in-stream (byte-array-input-stream/new in)]
              (channel-exec/set-input-stream channel in-stream)
              in-stream)

            (= :stream in)
            (let [in-output-stream (output-stream/new)
                  in-stream (input-stream/new in-output-stream pipe-buffer-size)]
              (channel-exec/set-input-stream channel in-stream)
              (output-stream/make-proxy in-output-stream))

            (keyword? in) ;; pod input-stream
            (do
              (channel-exec/set-input-stream channel in)
              in)

            :else ;; bb InputStream
            (do
              (->> (input-stream/new-pod-proxy
                    {:available (fn []
                                  (.available in))
                     :close (fn []
                              (.close in))
                     :mark #(.mark in %)
                     :mark-supported #(.markSupported in)
                     :read (fn
                             ([]
                              (.read in) ;; returns int
                              )
                             ([len]
                              (let [buff (byte-array len)
                                    bytes-read (.read in buff)]
                                (if (neg? bytes-read)
                                  [bytes-read nil]
                                  [bytes-read (java.util.Arrays/copyOfRange buff 0 bytes-read)]))))
                     :reset #(.reset in)
                     :skip #(.skip in %)})
                   (channel-exec/set-input-stream channel))
              in))

          out-stream
          (cond
            (= :string out)
            (let [out-stream (byte-array-output-stream/new)]
              (channel-exec/set-output-stream channel out-stream)
              out-stream)

            (= :bytes out)
            (let [out-stream (byte-array-output-stream/new)]
              (channel-exec/set-output-stream channel out-stream)
              out-stream)

            (or (nil? out) (= :stream out))
            (let [out-stream (output-stream/new)
                  out-input-stream (input-stream/new out-stream pipe-buffer-size)]
              (channel-exec/set-output-stream channel out-stream)
              (input-stream/make-proxy out-input-stream))

            (keyword? out) ;; output-stream pod reference
            (do
              (channel-exec/set-output-stream channel out)
              out)

            :else ;; bb OutputStream instance
            (let [out-stream (output-stream/new-pod-proxy
                              {:close #(.close out)
                               :flush #(.flush out)
                               :write (fn [bytes]
                                        (.write out bytes))})]
              (channel-exec/set-output-stream channel out-stream)
              out-stream))

          err-stream
          (cond
            (= :string err)
            (let [err-stream (byte-array-output-stream/new)]
              (channel-exec/set-error-stream channel err-stream)
              err-stream)

            (= :bytes err)
            (let [err-stream (byte-array-output-stream/new)]
              (channel-exec/set-error-stream channel err-stream)
              err-stream)

            (or (nil? err) (= :stream err))
            (let [err-stream (output-stream/new)
                  err-input-stream (input-stream/new err-stream pipe-buffer-size)]
              (channel-exec/set-error-stream channel err-stream)
              (input-stream/make-proxy err-input-stream))

            (keyword? err) ;; output-stream pod reference
            (do
              (channel-exec/set-error-stream channel err)
              err)

            :else ;; bb OutputStream instance
            (let [err-stream (output-stream/new-pod-proxy
                              {:close #(.close err)
                               :flush #(.flush err)
                               :write (fn [bytes]
                                        (.write err bytes))})]
              (channel-exec/set-error-stream channel err-stream)
              err-stream))]

      (channel-exec/connect channel)

      {:channel channel
       :out out-stream
       :err err-stream
       :in in-stream}
      )))

(defn wait
  "waits until a ssh exec remote process has finished executing and then
  returns the exit code. Optionally pass a timeout value in
  milliseconds. If the timeout is reached and the process has not
  finished then returns `nil`."
  [{:keys [channel]} & [timeout]]
  (let [status (channel-exec/get-exit-status channel)]
    (cond
      (<= 0 status)
      status

      (and timeout (<= timeout 0))
      nil

      timeout
      (let [deadline (+ (System/nanoTime) (* timeout 1000000))]
        (loop [remaining (- deadline (System/nanoTime))]
          (when (pos? remaining)
            (Thread/sleep (min (inc (/ remaining 1000000)) 100))
            (let [status (channel-exec/get-exit-status channel)]
              (if (<= 0 status)
                status
                (recur (- deadline (System/nanoTime))))))))

      :else ;; no timeout, block forever
      (loop []
        (Thread/sleep 100)
        (let [status (channel-exec/get-exit-status channel)]
          (if (<= 0 status)
            status
            (recur)))))))
