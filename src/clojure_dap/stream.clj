(ns clojure-dap.stream
  "Tools to work with DAP streams and streams in general."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [de.otto.nom.core :as nom]
            [cognitect.anomalies :as anom]
            [jsonista.core :as json]
            [taoensso.timbre :as log]
            [manifold.stream :as s]
            [clojure-dap.util :as util]
            [clojure-dap.schema :as schema]))

(def header-sep "\r\n")
(def double-header-sep (str header-sep header-sep))

(schema/define! ::io
  [:map
   [:input [:fn s/stream?]]
   [:output [:fn s/stream?]]])

(def ^:dynamic *stream-buffer-size* 1024)

(defn io
  "Create an input/output stream pair. Input is coming towards your code, output is heading out from your code."
  []
  {:input (s/stream *stream-buffer-size*)
   :output (s/stream *stream-buffer-size*)})
(m/=> io [:=> [:cat] ::io])

(defn close-io!
  "Closes the input and output of an IO pair."
  [{:keys [input output]}]
  (s/close! input)
  (s/close! output))
(m/=> close-io! [:=> [:cat ::io] nil?])

(defn parse-header
  "Given a header string of the format 'Content-Length: 119\\r\\n\\r\\n' it returns a map containing the key value pairs."
  [header]
  (try
    (into
     {}
     (map
      (fn [line]
        (let [[k v] (str/split line #": " 2)]
          [(keyword k) (json/read-value v)])))
     (str/split-lines (str/trim-newline header)))
    (catch Exception e
      (nom/fail
       ::anom/incorrect
       {::anom/message "Failed to parse DAP header"
        ::header header
        ::error (Throwable->map e)}))))
(m/=> parse-header [:=> [:cat string?] (schema/result [:map-of keyword? any?])])

(defn render-header
  "Turns a map of k->v into a header string."
  [x]
  (str
   (str/join
    (map
     (fn [[k v]]
       (str (name k) ": " v header-sep))
     x))
   header-sep))
(m/=> render-header [:=> [:cat [:or nil? [:map-of keyword? any?]]] string?])

(defn read-message
  "Reads a DAP message from the input stream. Assumes a few things: The first character we're going to read will be the beginning of a new messages header AND the stream will consist of single characters.

  It works by reading the header (Content-Length: 119\\r\\n\\r\\n) until a double \\r\\n\\r\\n at which point it knows the Content-Length and can read the rest of the message.

  Once read, we decode the JSON body and validate it against the JSON schemas before returning the valid message or an anomaly.

  Will block until a message is read!"
  [input-stream]
  (loop [header-buffer ""]
    (let [next-char @(s/take! input-stream)]
      (if (char? next-char)
        (let [header-buffer (str header-buffer next-char)]
          (if (or (= header-buffer header-sep)
                  (str/ends-with? header-buffer double-header-sep))
            (nom/let-nom> [{:keys [Content-Length] :as headers}
                           (parse-header header-buffer)
                           body (str/join @(s/take! (s/batch Content-Length input-stream)))]
              (try
                (let [parsed (json/read-value body json/keyword-keys-object-mapper)]
                  (nom/with-nom [(schema/validate ::schema/message parsed)]
                    parsed))
                (catch Exception e
                  (nom/fail
                   ::anom/incorrect
                   {::anom/message "Failed to parse DAP message JSON"
                    ::headers headers
                    ::body body
                    ::error (Throwable->map e)}))))
            (recur header-buffer)))
        (nom/fail
         ::anom/incorrect
         {::anom/message "Received a non-character while reading the next DAP message. A nil probably means the stream closed."
          ::value next-char})))))
(m/=>
 read-message
 [:=>
  [:cat [:fn s/stream?]]
  (schema/result [:map-of keyword? any?])])

(defn render-message
  "Takes a DAP message, validates it against the various possible schemas and then encodes it as a DAP JSON message with a header. This string can then be sent across the wire to the development tool."
  [message]
  (nom/with-nom [(schema/validate ::schema/message message)]
    (let [encoded (json/write-value-as-string message)]
      (str (render-header {:Content-Length (count encoded)}) encoded))))
(m/=>
 render-message
 [:=>
  [:cat [:or [:map-of keyword? any?] nil?]]
  (schema/result string?)])

(defn java-io->io
  "Takes a java.io.Reader and java.io.Writer and attaches them to a manifold stream IO pair. The returned io pair works on a character by character basis for reading into input and string basis for outputting to the writer."
  [{:keys [reader writer]}]

  (let [{:keys [input output] :as io-pair} (io)]
    (util/with-thread ::java-io-input-reader
      (loop []
        (when-not (s/closed? input)
          (let [char-int
                (try
                  (.read reader)
                  (catch Exception e
                    (log/error e "Error while reading in java-io->io")))]
            (if (or (nil? char-int) (= -1 char-int))
              (s/close! input)
              (do
                (s/put! input (char char-int))
                (recur)))))))

    (util/with-thread ::java-io-output-writer
      (loop []
        (when-not (s/closed? output)
          (when-let [to-write @(s/take! output)]
            (try
              (.write writer to-write)
              (.flush writer)
              (catch java.io.IOException ex
                (log/error ex "Error while writing to writer in java-io->io, closing output stream since it's probably closed")
                (s/close! output)))
            (recur)))))

    io-pair))
(m/=>
 java-io->io
 [:=>
  [:cat
   [:map
    [:writer [:fn #(instance? java.io.Writer %)]]
    [:reader [:fn #(instance? java.io.Reader %)]]]]
  ::io])
