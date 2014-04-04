(ns leiningen.catapult
  "Proxy TCP/IP nREPL connections to Drawbridge."
  (:require [clojure.core.async :refer [<!! >!! alt!! chan close! put! thread timeout]]
            [cemerick.drawbridge.client :refer [ring-client-transport]]
            [clojure.tools.nrepl.transport :as t]
            [me.raynes.fs :as fs])
  (:import (java.net ServerSocket)))

;; vim-fireplace (and maybe others?) frequently close and reopen the
;; nREPL TCP/IP connection -- perhaps after each command? -- and yet
;; expect their session id to be valid across connections.  That didn't
;; work for my initial catapult approach, which created a new Drawbridge
;; client for each incoming nREPL connection (this constantly creating
;; new sessions on the Drawbridge server).
;;
;; Among other things, messages sent in between nREPL TCP/IP connections
;; were sent on a session that would never be seen again and so the
;; vim-fireplace REPL would almost immediately stall.
;;
;; The solution is to use a single Drawbridge client and then map
;; multiple incoming nREPL TCP/IP connections onto that client.  The
;; trick is that we need to know which session id(s) are on each TCP/IP
;; connection so that we can properly route responses from Drawbridge
;; back to each TCP/IP connection.

(defn recv-chan
  [transport]
  (let [c (chan)]
    (thread
      (try
        (loop []
          ;; Remove this once Drawbridge supports hanging gets.
          (<!! (timeout 100))
          (>!! c (t/recv transport))
          (recur))
        (finally
          (println "Closed")
          (close! c))))
    c))

;; catapult-engine has two states: proxying messages, and creating a new
;; session.  In the former, we just copy messages back and forth.  In
;; the latter, we still copy messages from Drawbridge back to the
;; session channels, but we don't accept new requests until the new
;; session has been created.  This is because multiple nREPL TCP/IP
;; connections could be open and, in theory, they could try to clone new
;; sessions simultaneously.  By going synchronous during session
;; creation we ensure that we learn the new session id.
;;
;; nrepl-engine creates a single channel for itself and sends that in to
;; every catapult-engine request.  Then the catapult engine can either
;; send back the synchronous :new-session response or just make the
;; received channel the interested party for the session that was in the
;; request (bumping out any other channel that might have been there in
;; case of a reconnect).
;;
;; One issue here is that we might keep putting things into a closed
;; channel if the nREPL TCP/IP connection dies without reading
;; everything from the last session.  Unsolicited data is certainly
;; possible (from a future, for example), so we probably can't ignore
;; this problem.
;;
;; I think that we should detect a closed channel and create a new
;; temporary buffer channel in that situation.  Then when an nREPL
;; connection comes along again we flush the temporary buffer channel
;; into that new nREPL channel before swapping in the new channel.
;;
;; For now though we will assume that nREPL TCP/IP connections consume
;; all of the data and so we won't get writes into a closed channel.
;; This seems to work for vim-fireplace.

(defn nrepl-engine
  [catapult-chan sock]
  (println "nREPL connection accepted.")
  (let [transport (t/bencode sock)
        req-chan (recv-chan transport)
        res-chan (chan)]
    (thread
      (loop []
        (alt!!
          req-chan ([msg]
                    (println ">>>" msg)
                    (when msg
                      (put! catapult-chan (assoc msg :res-chan res-chan))
                      (recur)))

          res-chan ([msg]
                    (t/send transport msg)
                    (recur))))
      (println "nREPL connection closed.")
      (close! res-chan))
    res-chan))

(def session-map
  "Map of session ids to the latest input transport on which that session id was seen."
  (atom {}))

(defn dispatch-response
  "Dispatches a message to the transport which last used the associated session id."
  [{:keys [session] :as msg}]
  (println "<<<" msg)
  (let [c (get @session-map session)]
    (put! c msg)))

(defn catapult-engine
  [url]
  (let [transport (ring-client-transport url)
        catapult-req-chan (chan)
        catapult-res-chan (recv-chan transport)]
    (thread
      ;; TODO Could put session-map here and play games with recur.
      (loop []
        (alt!!
          catapult-req-chan
          ([{:keys [op session res-chan] :as msg}]
           (when session
             ;; Associate this session with res-chan.
             (swap! session-map assoc session res-chan))

           ;; Send message to Drawbridge.
           (t/send transport (dissoc msg :res-chan))

           ;; Clone op?  If so, synchronously read responses until the
           ;; new session has been created.
           (when (= op "clone")
             (loop []
               (let [{:keys [new-session] :as msg} (<!! catapult-res-chan)]
                 (if new-session
                   ;; New session response?  Send the message directly
                   ;; to the requestor's response channel.  We're done
                   ;; with synchronous reads once that happens.
                   (do (put! res-chan msg)
                       (swap! session-map assoc new-session res-chan))

                   ;; Still looking for the new session response;
                   ;; dispatch this unrelated message and recur.
                   (do (dispatch-response msg)
                       (recur)))))))

          catapult-res-chan
          ([msg]
           (dispatch-response msg)))

        (recur)))
    catapult-req-chan))

(defn write-nrepl-port-files
  [project-path port]
  (spit (fs/file project-path ".nrepl-port") port)
  (.mkdirs (fs/file project-path "target" "repl"))
  (spit (fs/file project-path "target" "repl-port") port)
  (spit (fs/file project-path "target" "repl" "repl-port") port))

(defn ^:no-project-needed catapult
  "Proxy TCP/IP nREPL connections to Drawbridge.

   USAGE: lein catapult [url]
   Starts an nREPL server and proxies connections to that nREPL server
   to a Drawbridge server via HTTP(S)."
  ([project]
   (if-let [url (some-> project :catapult :url)]
     (catapult project url)
     (println "ERROR: Must provide a Drawbridge URL.")))
  ([project url]
   (let [catapult-chan (catapult-engine url)
         server (ServerSocket. 0)
         port (.getLocalPort server)]
     (println "Listening on port " port "; connected to Drawbridge.")
     (when-let [root-path (:root project)]
       (write-nrepl-port-files root-path port))
     (loop []
       (let [sock (.accept server)]
         (nrepl-engine catapult-chan sock)
         (recur))))))
