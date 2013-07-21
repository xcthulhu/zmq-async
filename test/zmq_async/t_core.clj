(ns zmq-async.t-core
  (:require [zmq-async.core :refer :all]
            [clojure.core.async :refer [go close! >!! <!! chan timeout alts!!]]
            [midje.sweet :refer :all])
  (:import org.zeromq.ZMQ))


(fact "Poller selects correct socket"
      (with-open [sock-A (doto (.createSocket context ZMQ/PULL)
                           (.bind "inproc://A"))
                  sock-B (doto (.createSocket context ZMQ/PULL)
                           (.bind "inproc://B"))]

        (doto (.createSocket context ZMQ/PUSH)
          (.connect "inproc://A")
          (.send "A message"))

        (poll [sock-A sock-B]) => ["A message" sock-A]))

(fact "ZMQ looper"
      (with-state-changes [(around :facts
                                   (let [acontrol (chan)
                                         zmq-control-addr "inproc://test-control"
                                         zmq-looper (doto (Thread. (zmq-looper (doto (.createSocket context ZMQ/PAIR)
                                                                                 (.bind zmq-control-addr))
                                                                               acontrol))
                                                      (.start))
                                         zcontrol (doto (.createSocket context ZMQ/PAIR)
                                                    (.connect zmq-control-addr))]

                                     ?form

                                     (send! zcontrol (pr-str :shutdown))
                                     (.join zmq-looper 100)
                                     (assert (not (.isAlive zmq-looper)))

                                     ;;Close any hanging ZeroMQ sockets.
                                     (doseq [s (.getSockets context)]
                                       (.close s))))]

        ;;TODO: rearchitect so that one concern can be tested at a time?
        ;;Then the zmq looper would need to use accessible mutable state instead of loop/recur...
        (fact "Opens sockets, conveys messages between sockets and async control channel"
              (let [test-addr "inproc://open-test"
                    test-id "open-test"
                    test-msg "hihi"]

                (send! zcontrol (pr-str [:open test-addr ZMQ/PAIR :bind test-id]))
                ;;TODO: this sleep is gross; how to wait for ZMQ socket to open?
                (Thread/sleep 50)

                (with-open [sock  (.createSocket context ZMQ/PAIR)]
                  (.connect sock test-addr)
                  (.send sock test-msg)

                  ;;passes along recieved messages
                  (<!! acontrol) => [test-id test-msg]

                  ;;sends messages when asked to
                  (send! zcontrol (pr-str [test-id test-msg]))
                  (Thread/sleep 50)
                  (.recvStr sock ZMQ/NOBLOCK) => test-msg)))))


(fact "core.async looper"
      (with-state-changes [(around :facts
                                   (let [acontrol (chan)
                                         zmq-control-addr "inproc://test-control"
                                         zcontrol (doto (.createSocket context ZMQ/PAIR)
                                                    (.bind zmq-control-addr))

                                         async-looper (doto (Thread. (async-looper acontrol
                                                                                   (doto (.createSocket context ZMQ/PAIR)
                                                                                     (.connect zmq-control-addr))))
                                                        (.start))]

                                     ?form

                                     (close! acontrol)
                                     (.join async-looper 100)
                                     (assert (not (.isAlive async-looper)))

                                     ;;Close any hanging ZeroMQ sockets.
                                     (doseq [s (.getSockets context)]
                                       (.close s))))]

        (fact "Tells ZMQ looper to shutdown when the async thread's control channel is closed"
              (close! acontrol)
              (read-string (.recvStr zcontrol)) => :shutdown)

        (fact "Closes all open sockets when the async thread's control channel is closed"
              (let [test-addr "ipc://test-addr"
                    [send recv] (request-socket test-addr :bind acontrol)
                    [_ addr _ _ sock-id] (read-string (.recvStr zcontrol))]

                addr => test-addr

                ;;close the control socket
                (close! acontrol)
                ;;the ZMQ thread was told to close the socket we opened earlier
                (read-string (.recvStr zcontrol)) => [:close sock-id]))

        (fact "Forwards messages recieved from ZeroMQ thread to appropriate core.async channel."
              (let [test-msg "hihi"
                    [send recv] (request-socket "ipc://test-addr" :bind acontrol)
                    [_ _ _ _ sock-id] (read-string (.recvStr zcontrol))]

                (>!! acontrol [sock-id test-msg])
                (<!! recv) => test-msg))

        (fact "Forwards messages recieved from core.async 'send' channel to ZeroMQ thread."
              (let [test-msg "hihi"
                    [send recv] (request-socket "ipc://test-addr" :bind acontrol)
                    [_ _ _ _ sock-id] (read-string (.recvStr zcontrol))]
                (>!! send test-msg)
                (read-string (.recvStr zcontrol)) => [sock-id test-msg]))))





(fact "Integration"
      (with-state-changes [(around :facts
                                   (let [acontrol (chan)
                                         zmq-control-addr "inproc://test-control"
                                         zmq-looper (doto (Thread. (zmq-looper (doto (.createSocket context ZMQ/PAIR)
                                                                                 (.bind zmq-control-addr))
                                                                               acontrol))
                                                      (.start))
                                         async-looper (doto (Thread. (async-looper acontrol
                                                                                   (doto (.createSocket context ZMQ/PAIR)
                                                                                     (.connect zmq-control-addr))))
                                                        (.start))]

                                     ?form

                                     (close! acontrol)
                                     (.join async-looper 100)
                                     (assert (not (.isAlive async-looper)))

                                     (.join zmq-looper 100)
                                     (assert (not (.isAlive zmq-looper)))

                                     ;;Close any hanging ZeroMQ sockets.
                                     (doseq [s (.getSockets context)]
                                       (.close s))))]

        (fact "raw->wrapped"
              (let [addr "inproc://test-addr"
                    test-msg "hihi"
                    [send recv] (pair-socket addr :bind acontrol)
                    _ (Thread/sleep 50) ;;gross!
                    raw (doto (.createSocket context ZMQ/PAIR)
                          (.connect addr))]

                (.send raw test-msg)
                (<!! recv) => test-msg))

        (fact "wrapped->raw"
              (let [addr "inproc://test-addr"
                    test-msg "hihi"
                    [send recv] (pair-socket addr :bind acontrol)
                    _ (Thread/sleep 50) ;;gross!
                    raw (doto (.createSocket context ZMQ/PAIR)
                          (.connect addr))]
                (>!! send test-msg)
                (Thread/sleep 50) ;;gross!
                (.recvStr raw ZMQ/NOBLOCK) => test-msg))

        (fact "wrapped pair -> wrapped pair"
              (let [addr "inproc://test-addr"
                    test-msg "hihi"
                    [s-send s-recv] (pair-socket addr :bind acontrol)
                    [c-send c-recv] (pair-socket addr :connect acontrol)]
                (Thread/sleep 50)
                (>!! c-send test-msg)
                (<!! s-recv) => test-msg))

        (fact "wrapped req <-> wrapped rep, go/future"
              (let [addr "inproc://test-addr"
                    [s-send s-recieve] (reply-socket addr :bind acontrol)
                    [c-send c-recieve] (request-socket addr :connect acontrol)
                    n 5
                    server (go
                             (dotimes [_ n]
                               (assert (= "ping" (<! s-recieve))
                                       "server did not receive ping")
                               (>! s-send "pong"))
                             :success)

                    client (future
                             (dotimes [_ n]
                               (>!! c-send "ping")
                               (assert (= "pong" (<!! c-recieve))
                                       "client did not receive pong"))
                             :success)]

                (deref client 500 :fail) => :success
                (close! c-send)
                (close! s-send)
                (close! server)
                (<!! server) => :success))

        (fact "wrapped req <-> wrapped rep, go/go"
              (let [addr "inproc://test-addr"
                    [s-send s-recieve] (reply-socket addr :bind acontrol)
                    [c-send c-recieve] (request-socket addr :connect acontrol)
                    n 5
                    server (go
                             (dotimes [_ n]
                               (assert (= "ping" (<! s-recieve))
                                       "server did not receive ping")
                               (>! s-send "pong"))
                             :success)

                    client (go
                             (dotimes [_ n]
                               (>! c-send "ping")
                               (assert (= "pong" (<! c-recieve))
                                       "client did not receive pong"))
                             :success)]

                  ;;TODO: (<!! client 500 :fail) would be cooler
                  (let [[val c] (alts!! [client (timeout 500)])]
                      (if (= c client) val :fail)) => :success
                      (close! c-send)
                      (close! s-send)
                      (close! server)
                      (<!! server) => :success))))
