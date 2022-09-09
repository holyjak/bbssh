(ns pod.epiccastle.bbssh.agent
  (:require [pod.epiccastle.bbssh.impl.agent :as agent]
            [pod.epiccastle.bbssh.cleaner :as cleaner]))

(defn new
  "Make a new JSch agent. A JSch agent is not an \"ssh agent\".
  It is the base java class that holds and controls the
  sessions."
  []
  (cleaner/register (agent/new)))

(defn get-session
  "Construct a new JSch connection session. Does not start the ssh
  connection.
  "
  [agent username host port]
  (cleaner/register
   (agent/get-session (cleaner/split-key agent) username host port)))

(defn get-identity-repository
  "Get the current identity-repository from the agent."
  [agent]
  (cleaner/register
   (agent/get-identity-repository
    (cleaner/split-key agent))))

(defn set-identity-repository
  "Set the identity-repository the agent should use."
  [agent identity-repository]
  (agent/set-identity-repository
   (cleaner/split-key agent)
   (cleaner/split-key identity-repository)))
