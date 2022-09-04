(ns pod.epiccastle.bbssh.impl.key-pair
  (:require [bbssh.impl.references :as references]
            [bbssh.impl.utils :as utils])
  (:import [com.jcraft.jsch JSch KeyPair]))

;; pod.epiccastle.bbssh.impl.* are invoked on pod side.

(set! *warn-on-reflection* true)

(defn generate [agent key-type key-size]
  (references/add-instance
   (KeyPair/genKeyPair
    ^JSch (references/get-instance agent)
    ^int ({:dsa KeyPair/DSA
           :rsa KeyPair/RSA
           :ecdsa KeyPair/ECDSA
           :ed25519 KeyPair/ED25519
           :ed448 KeyPair/ED448}
          key-type)
    ^int key-size)))


(defn set-passphrase [key-pair passphrase]
  (.setPassphrase
   ^KeyPair (references/get-instance key-pair)
   ^String passphrase))

(defn write-private-key
  ([key-pair filename]
   (.writePrivateKey
    ^KeyPair (references/get-instance key-pair)
    ^String filename))
  ([key-pair filename passphrase]
   (.writePrivateKey
    ^KeyPair (references/get-instance key-pair)
    ^String filename
    ^bytes (utils/decode-base64 passphrase))))

(defn write-public-key [key-pair filename comment]
  (.writePublicKey
   ^KeyPair (references/get-instance key-pair)
   ^String filename
   ^String comment))

(defn get-finger-print [key-pair]
  (.getFingerPrint
   ^KeyPair (references/get-instance key-pair))
  )

(defn get-public-key-blob [key-pair]
  (utils/encode-base64
   (.getPublicKeyBlob
    ^KeyPair (references/get-instance key-pair))))

(defn get-key-size [key-pair]
  (.getKeySize
    ^KeyPair (references/get-instance key-pair)))
