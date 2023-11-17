(ns clojure-dap.server.handler-test
  (:require [clojure.test :as t]
            [matcher-combinators.test]
            [clojure-dap.schema :as schema]
            [clojure-dap.protocol :as protocol]
            [clojure-dap.server :as server]
            [clojure-dap.server.handler :as handler]
            [clojure-dap.debuggee :as debuggee]
            [clojure-dap.debuggee.fake :as fake-debuggee]))

(t/deftest auto-seq
  (t/testing "starts at 1 and auto increments"
    (let [next-seq (server/auto-seq)]
      (t/is (= 1 (next-seq)))
      (t/is (= 2 (next-seq)))
      (t/is (= 3 (next-seq))))))

(t/deftest handle-client-input
  (t/testing "initialize request"
    (t/is (= [{:body handler/initialised-response-body
               :command "initialize"
               :request_seq 1
               :seq 1
               :success true
               :type "response"}
              {:event "initialized", :seq 2, :type "event"}]
             (handler/handle-client-input
              {:next-seq (server/auto-seq)
               :debuggee! (atom nil)
               :input
               {:seq 1
                :type "request"
                :command "initialize"
                :arguments {:adapterID "12345"}}}))))

  (t/testing "attach request"
    (t/testing "success"
      (let [debuggee! (atom nil)]
        (t/is (= [{:command "attach"
                   :request_seq 1
                   :seq 1
                   :success true
                   :type "response"
                   :body {}}]
                 (handler/handle-client-input
                  {:next-seq (server/auto-seq)
                   :debuggee! debuggee!
                   :input
                   {:seq 1
                    :type "request"
                    :command "attach"
                    :arguments {:clojure_dap {:type "fake"}}}})))
        (t/is (nil? (schema/validate ::debuggee/debuggee @debuggee!)))))

    (t/testing "failure due to bad inputs"
      (let [debuggee! (atom nil)]
        (t/is (match?
               [{:command "attach"
                 :request_seq 1
                 :seq 1
                 :success false
                 :type "response"
                 :message #"Failed to validate against schema :clojure-dap.server.handler/attach-opts: \[:clojure_dap \{:type \[\"should be either fake or nrepl\"\]\}\]"
                 :body {:value {:clojure_dap {:type "ohno"}}}}]
               (handler/handle-client-input
                {:next-seq (server/auto-seq)
                 :debuggee! debuggee!
                 :input
                 {:seq 1
                  :type "request"
                  :command "attach"
                  :arguments {:clojure_dap {:type "ohno"}}}})))
        (t/is (nil? @debuggee!))))

    (t/testing "failure due to a connection error (for example)"
      (let [debuggee! (atom nil)]
        (t/is (= [{:command "attach"
                   :request_seq 1
                   :seq 1
                   :success false
                   :type "response"
                   :message "[:clojure-dap.debuggee.fake/oh-no] No message"
                   :body {:value {:clojure_dap
                                  {:type "fake"
                                   :fake {:create-error? true}}}}}]
                 (handler/handle-client-input
                  {:next-seq (server/auto-seq)
                   :debuggee! debuggee!
                   :input
                   {:seq 1
                    :type "request"
                    :command "attach"
                    :arguments {:clojure_dap
                                {:type "fake"
                                 :fake {:create-error? true}}}}})))
        (t/is (nil? @debuggee!)))))

  (t/testing "disconnect request"
    (t/is (= [{:command "disconnect"
               :request_seq 1
               :seq 1
               :success true
               :type "response"
               :body {}}]
             (handler/handle-client-input
              {:next-seq (server/auto-seq)
               :debuggee! (atom (fake-debuggee/create {}))
               :input
               {:arguments {:restart false, :terminateDebuggee true}
                :command "disconnect"
                :type "request"
                :seq 1}}))))

  (t/testing "configurationDone request"
    (t/is (= [{:command "configurationDone"
               :request_seq 1
               :seq 1
               :success true
               :type "response"
               :body {}}]
             (handler/handle-client-input
              {:next-seq (server/auto-seq)
               :debuggee! (atom (fake-debuggee/create {}))
               :input
               {:arguments {}
                :command "configurationDone"
                :type "request"
                :seq 1}}))))

  (t/testing "setBreakpoints request"
    (t/testing "before attach"
      (t/is (match?
             [{:command "setBreakpoints"
               :request_seq 1
               :seq 1
               :success false
               :type "response"
               :message #"Debuggee not initialised"
               :body {}}]
             (handler/handle-client-input
              {:next-seq (server/auto-seq)
               :debuggee! (atom nil)
               :input
               {:arguments {:source {:path "foo.clj"}}
                :command "setBreakpoints"
                :type "request"
                :seq 1}}))))

    (t/testing "success"
      (t/is (= [{:command "setBreakpoints"
                 :request_seq 1
                 :seq 1
                 :success true
                 :type "response"
                 :body {}}]
               (handler/handle-client-input
                {:next-seq (server/auto-seq)
                 :debuggee! (atom (fake-debuggee/create {}))
                 :input
                 {:arguments {:source {:path "foo.clj"}}
                  :command "setBreakpoints"
                  :type "request"
                  :seq 1}}))))

    (t/testing "failure"
      (t/is (= [{:command "setBreakpoints"
                 :request_seq 1
                 :seq 1
                 :success false
                 :type "response"
                 :message "Setting breakpoints failed"
                 :body {}}]
               (handler/handle-client-input
                {:next-seq (server/auto-seq)
                 :debuggee! (atom (fake-debuggee/create {:fail? true}))
                 :input
                 {:arguments {:source {:path "foo.clj"}}
                  :command "setBreakpoints"
                  :type "request"
                  :seq 1}})))))

  (t/testing "evaluate request"
    (t/testing "before attach"
      (t/is (match?
             [{:command "evaluate"
               :request_seq 1
               :seq 1
               :success false
               :type "response"
               :message #"Debuggee not initialised"
               :body {}}]
             (handler/handle-client-input
              {:next-seq (server/auto-seq)
               :debuggee! (atom nil)
               :input
               {:arguments {:expression "(+ 1 2)"}
                :command "evaluate"
                :type "request"
                :seq 1}}))))

    (t/testing "success"
      (t/is (= [{:command "evaluate"
                 :request_seq 1
                 :seq 1
                 :success true
                 :type "response"
                 :body {}}]
               (handler/handle-client-input
                {:next-seq (server/auto-seq)
                 :debuggee! (atom (fake-debuggee/create {}))
                 :input
                 {:arguments {:expression "(+ 1 2)"}
                  :command "evaluate"
                  :type "request"
                  :seq 1}}))))

    (t/testing "failure"
      (t/is (= [{:command "evaluate"
                 :request_seq 1
                 :seq 1
                 :success false
                 :type "response"
                 :message "Evaluate failed"
                 :body {}}]
               (handler/handle-client-input
                {:next-seq (server/auto-seq)
                 :debuggee! (atom (fake-debuggee/create {:fail? true}))
                 :input
                 {:arguments {:expression "(+ 1 2)"}
                  :command "evaluate"
                  :type "request"
                  :seq 1}}))))))

(t/deftest handle-anomalous-client-input
  (t/testing "given an anomaly it returns an output event containing an explanation"
    (t/is (match?
           [{:event "output",
             :seq 1,
             :type "event",
             :body {:category "important"
                    :data {:event "some unknown event"
                           :foo true
                           :type "event"}
                    :output #"^\[:cognitect.anomalies/incorrect\] Failed to validate against schema :clojure-dap.protocol/message: \d+ JSON Validation errors: #: required key \[seq\] not found, "}}]
           (handler/handle-anomalous-client-input
            {:anomaly (schema/validate
                       ::protocol/message
                       {:type "event"
                        :event "some unknown event"
                        :foo true})
             :next-seq (server/auto-seq)})))))
