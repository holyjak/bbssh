(ns pod.epiccastle.bbssh.scp
  "Implementation of the scp protocol"
  (:require [pod.epiccastle.bbssh.utils :as utils]
            [pod.epiccastle.bbssh.core :as bbssh]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import [java.util Arrays]
           [java.io File]
           [java.time Instant]))

(def default-buffer-size (* 256 1024))

;;
;; send and receive commands
;;
(defn- recv-ack [{:keys [out]}]
  (let [code (.read out)]
    (when-not (zero? code)
      ;; read scp error message
      (let [msg (loop [c (.read out)
                       s ""]
                  (if (#{10 13 -1 0} c)
                    s
                    (recur (.read out) (str s (char c)))))]
        (throw (ex-info "scp error" {:type ::scp-error
                                     :code code
                                     :msg msg}))))))
(defn- send-ack
  "Send acknowledgement to the specified output stream"
  [{:keys [in]}]
  (.write in (byte-array [0]))
  (.flush in))

(defn- send-command
  "Send command to the specified output stream"
  [{:keys [in] :as process} cmd-string]
  (.write in (.getBytes (str cmd-string "\n")))
  (.flush in)
  (recv-ack process))

(defn- scp-read-until-newline
  "Read from the remote process until a newline character.
  Assumes that the incoming data stops after the newline
  to wait for an ack."
  [{:keys [out]}]
  (let [buffer-size 4096
        buffer (byte-array buffer-size)]
    (loop [offset 0]
      (let [bytes-read (.read out buffer offset (- buffer-size offset))
            last-byte (aget buffer (dec (+ offset bytes-read)))]
        (if (= \newline (char last-byte))
          (String. buffer 0 (+ offset bytes-read))
          (recur (+ offset bytes-read)))))))

;;
;; copy streams
;;
(defn- io-copy-with-progress
  [source output-stream
   & [{:keys [size
              encoding
              progress-fn
              buffer-size
              progress-context]
       :or {buffer-size default-buffer-size
            encoding "utf-8"}}]]
  (let [is-string? (string? source)
        is-file? (= File (class source))
        data (if is-string? (.getBytes source encoding) source)
        size (or size
                 (if is-file? (.length source) (count data)))
        input-stream (io/input-stream data)
        chunk (byte-array buffer-size)]
    (loop [offset 0
           progress-context progress-context]
      (if (= 0 size)
        (progress-fn progress-context source offset size)
        (let [bytes-read (.read input-stream chunk)]
          (if (= -1 bytes-read)
            progress-context
            (let [offset (+ offset bytes-read)]
              (io/copy
               (if (= bytes-read buffer-size)
                 chunk ;; full buffer read
                 (Arrays/copyOfRange chunk 0 bytes-read) ;; partial read
                 )
               output-stream)
              (.flush output-stream)
              (recur
               offset
               (progress-fn progress-context source offset size)))))))))

(defn- io-copy-num-bytes
  [source output-stream
   length
   {:keys [buffer-size
           progress-context
           progress-fn]
    :or {buffer-size default-buffer-size}}]
  (let [buffer (byte-array buffer-size)]
    (loop [read-offset 0
           progress-context progress-context]
      (if (zero? length)
        (if progress-fn
          (progress-fn progress-context source read-offset length)
          progress-context)
        (let [bytes-read
              (.read source
                     buffer
                     0
                     (min (- length read-offset) buffer-size))]
          (if (= -1 bytes-read)
            progress-context
            (do
              (.write output-stream buffer 0 bytes-read)
              (let [read-offset (+ read-offset bytes-read)
                    progress-context
                    (if progress-fn
                      (progress-fn progress-context source read-offset length)
                      progress-context)]
                (if (< read-offset length)
                  (recur read-offset
                         progress-context)
                  progress-context)))))))))

;;
;; scp from local to remote
;;
(defn- scp-copy-file
  [{:keys [in out] :as process}
   file
   {:keys [preserve-times? preserve-mode? mode buffer-size progress-fn]
    :or {mode 0644
         buffer-size default-buffer-size}
    :as options}]
  (when preserve-times?
    (send-command
     process
     (format "T%d 0 %d 0"
             (utils/last-modified-time file)
             (utils/last-access-time file))))
  (send-command
   process
   (format "C%04o %d %s"
           (if preserve-mode? (utils/file-mode file) mode)
           (.length file)
           (.getName file)))
  (let [progress-context
        (if progress-fn
          (io-copy-with-progress file in options)
          (io/copy file in :buffer-size buffer-size))]
    (send-ack process)
    (recv-ack process)
    progress-context))

(defn- scp-copy-dir
  [{:keys [in out] :as process}
   dir
   {:keys [preserve-times? preserve-mode? dir-mode progress-fn progress-context]
    :or {dir-mode 0755}
    :as options}]
  (when preserve-times?
    (send-command
     process
     (format "T%d 0 %d 0"
             (utils/last-modified-time dir)
             (utils/last-access-time dir))))
  (send-command
   process
   (format "D%04o 0 %s"
           (if preserve-mode? (utils/file-mode dir) dir-mode)
           (.getName dir)))
  (let [progress-context
        (loop [[file & remain] (.listFiles dir)
               progress-context progress-context]
          (if file
            (cond
              (.isFile file)
              (recur
               remain
               (scp-copy-file process file
                              (assoc options
                                     :progress-context
                                     progress-context)))

              (.isDirectory file)
              (recur
               remain
               (scp-copy-dir process file
                             (assoc options
                                    :progress-context
                                    progress-context)))

              :else
              (recur remain progress-context))

            progress-context))]
    (send-command process "E")
    progress-context))

(defn- scp-copy-data
  [{:keys [in out] :as process}
   [source info]
   {:keys [preserve-times? mode buffer-size progress-fn]
    :or {mode 0644
         buffer-size default-buffer-size}
    :as options}]
  (when-not (:name info)
    (throw (ex-info "scp data info must contain :name"
                    {:type ::name-error})))
  (let [data (if (string? source)
               (.getBytes source (:encoding info "utf-8"))
               source)
        size (or (:size info) (count data))]
    (when (and preserve-times?
               (:mtime info)
               (:atime info))
      (send-command
       process
       (format "T%d 0 %d 0"
               (:mtime info)
               (:atime info))))
    (send-command
     process
     (format "C%04o %d %s"
             (:mode info mode)
             size
             (:name info)))
    (let [progress-context
          (if progress-fn
            (io-copy-with-progress source in
                                   (assoc options
                                          :size size
                                          :encoding (:encoding info "utf-8")))
            (io/copy source in :buffer-size buffer-size))]
      (send-ack process)
      (recv-ack process)
      progress-context)))

(defn scp-to
  "copy local paths to remote path"
  [local-sources remote-path
   {:keys [session
           extra-flags
           recurse?
           preserve-times?
           preserve-mode?
           progress-context
           scp-command-fn]
    :or {extra-flags ""
         preserve-times? true
         preserve-mode? true
         scp-command-fn
         (fn [cmd]
           (str "sh -c 'umask 0000;"
                cmd
                "'"))}
    :as options}
   ]
  (let [remote-command
        (scp-command-fn
         (string/join " "
                      ["scp"
                       extra-flags
                       (when recurse? "-r")
                       (when preserve-times? "-p")
                       "-t" ;; to
                       (utils/quote-path remote-path)
                       ]))

        {:keys [in out err channel] :as process}
        (bbssh/exec session remote-command {:in :stream})]
    (recv-ack process)
    (loop [[source & remain] local-sources
           progress-context progress-context
           ]
      (let [options (assoc options :progress-context progress-context)
            progress-context
            (cond
              (vector? source)
              (scp-copy-data process source options)

              (.isDirectory ^File source)
              (scp-copy-dir process source options)

              (.isFile ^File source)
              (scp-copy-file process source options))]
        (if remain
          (recur remain progress-context)
          (do
            (.close in)
            (.close out)
            (.close err)
            progress-context))))))

;;
;; scp from remote to local
;;
(defn- scp-stream-to-file
  ""
  [{:keys [out in] :as process} file mode length
   {:keys [progress-fn
           buffer-size]
    :or {buffer-size default-buffer-size}
    :as options}]
  (with-open [file-stream (io/output-stream file)]
    (io-copy-num-bytes out file-stream length options)))

(defn- scp-from-receive
  "scp commands copying from remote to local"
  [{:keys [out in] :as process}
   file {:keys [progress-fn
                progress-context
                preserve-time
                preserve-mode?]
         :as options}]
  ;;(prn file)
  (loop [command (scp-read-until-newline process)
         file file
         times nil
         depth 0
         progress-context progress-context]
    (send-ack process)
    (prn "...." file ">" depth "[" times "]")
    (prn command)
    (case (first command)
      \C ;; single file copy
      (let [[mode length filename] (-> command
                                       string/trim
                                       (subs 1)
                                       (string/split #" " 3))
            mode (edn/read-string mode) ;; octal
            length (edn/read-string length)
            new-file (if (and (.exists file)
                              (.isDirectory file))
                       (File. file filename)
                       file)]
        (prn 'file file 'new-file new-file)
        (when (.exists new-file)
          (.delete new-file))
        (utils/create-file new-file
                           (if preserve-mode?
                             mode
                             (:mode options)))
        (let [progress-context
              (scp-stream-to-file process new-file mode length
                                  (assoc options
                                         :progress-context
                                         progress-context))]
          (recv-ack process)
          (send-ack process)
          (when (and times preserve-time)
            (utils/update-file-times new-file times))
          (if (pos? depth)
            (recur
             (scp-read-until-newline process)
             file
             nil
             depth
             progress-context)
            progress-context)))

      \D ;; start directory copy
      (let [[mode _ filename] (-> command
                                  string/trim
                                  (subs 1)
                                  (string/split #" " 3))
            mode (edn/read-string mode) ;; octal
            dir (File. file filename)]
        (when (and (.exists dir) (not (.isDirectory dir)))
          (.delete dir))
        (when (not (.exists dir))
          (utils/create-dirs dir
                             (if preserve-mode?
                               mode
                               (:dir-mode options))))
        (when (and times preserve-time)
          (utils/update-file-times dir times))

        (recur
         (scp-read-until-newline process)
         dir
         nil
         (inc depth)
         progress-context))

      \E ;; end of directory
      (if (> depth 1)
        (recur
         (scp-read-until-newline process)
         (.getParentFile file)
         nil
         (dec depth)
         progress-context)
        progress-context)

      \T ;; timestamps
      (let [[mtime _ atime _] (-> command
                                  string/trim
                                  (subs 1)
                                  (string/split #" " 4))
            mtime (-> mtime
                      edn/read-string
                      Instant/ofEpochSecond)
            atime (-> atime
                      edn/read-string
                      Instant/ofEpochSecond)]
        (recur
         (scp-read-until-newline process)
         file
         [mtime atime]
         (dec depth)
         progress-context)))))

(defn scp-from
  "copy remote paths to local paths"
  [remote-path local-file {:keys [session
                                  extra-flags
                                  recurse?
                                  preserve-mode?
                                  preserve-times?
                                  scp-command-fn]
                           :or {extra-flags ""
                                preserve-mode? true
                                preserve-times? true
                                scp-command-fn identity}
                           :as options}
   ]
  (let [remote-command
        (scp-command-fn
         (string/join " "
                      ["scp"
                       extra-flags
                       (when recurse? "-r")
                       (when preserve-times? "-p")
                       "-f" ;; from
                       (utils/quote-path remote-path)
                       ]))

        {:keys [in out err channel] :as process}
        (bbssh/exec session remote-command {:in :stream})]
    (send-ack process)
    (let [progress-context (scp-from-receive process local-file options)]
      (.close in)
      (.close out)
      (.close err)
      progress-context)))
