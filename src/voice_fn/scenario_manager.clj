(ns voice-fn.scenario-manager
  "Scenario Manager is a way to build structured conversations with the underlying
  LLM. In enables you to create predefined conversation scenarios that follow a
  specific flow. Use it when the interaction is highly structured.

  The scenario manager works by appending the messages that define the current
  node to the existing llm context."
  (:require
   [clojure.core.async.flow :as flow]
   [malli.core :as m]
   [malli.error :as me]
   [taoensso.telemere :as t]
   [voice-fn.frame :as frame]
   [voice-fn.schema :as schema]))

(def ScenarioConfig
  [:and [:map {:closed true}
         [:initial-node :keyword]
         [:nodes [:map-of
                  :keyword
                  [:map {:closed true}
                   [:role-messages {:optional true} [:vector schema/LLMSystemMessage]]
                   [:task-messages [:vector schema/LLMSystemMessage]]
                   [:functions [:vector [:or
                                         schema/LLMFunctionToolDefinitionWithHandling
                                         schema/LLMTransitionToolDefinition]]]]]]]
   [:fn {:error/message "Initial node not defined"}
    (fn [sc]
      (boolean (get-in sc [:nodes (:initial-node sc)])))]
   [:fn {:error/fn (fn [{:keys [value]} _]
                     (let [nodes (set (keys (:nodes value)))
                           transitions (->> value
                                         :nodes
                                         vals
                                         (mapcat :functions)
                                         (keep (fn [f] (get-in f [:function :transition-to])))
                                         (remove nil?))
                           invalid-transition (first (remove nodes transitions))]
                       (when invalid-transition
                         (format "Unreachable node: %s" invalid-transition))))}
    (fn [{:keys [nodes]}]
      (let [defined-nodes (set (keys nodes))
            transitions (->> nodes
                          vals
                          (mapcat :functions)
                          (keep (fn [f] (get-in f [:function :transition-to])))
                          (remove nil?))]
        (every? defined-nodes transitions)))]])

(defprotocol Scenario
  (start [s] "Start the scenario")
  (set-node [s node] "Moves to the current node of the conversation")
  (current-node [s] "Get current node"))

(defn transition-fn
  "Transform a function declaration into a transition function. A transition
  function calls the original function handler, and then transitions the
  scenario to the :transition-to node from f

  scenario - scenario that will be transitioned
  tool - transition tool declaration. See `schema/LLMTransitionToolDefinition`
  "
  [scenario tool]
  (let [fndef (:function tool)
        handler (:handler fndef)
        next-node (:transition-to fndef)
        cb #(set-node scenario next-node)]
    (cond-> tool
      true (update-in [:function] dissoc :transition-to)
      true (assoc-in [:function :transition-cb] cb)
      (nil? handler) (assoc-in [:function :handler] (fn [_] {:status :success})))))

(defn validate-scenario
  [scenario] true)

(defn scenario-manager
  [{:keys [scenario-config flow flow-in-coord]}]
  (when-let [errors (me/humanize (m/explain ScenarioConfig scenario-config))]
    (throw (ex-info "Invalid scenario config" {:errors errors})))

  (let [current-node (atom nil)
        nodes (:nodes scenario-config)
        initialized? (atom false)]
    (reify Scenario
      (current-node [_] @current-node)
      (set-node [this node-id]
        (assert (get-in scenario-config [:nodes node-id]) (str "Invalid node: " node-id))
        (t/log! :info ["SCENARIO" "NEW NODE" node-id])
        (let [node (get nodes node-id)
              tools (mapv (partial transition-fn this) (:functions node))
              append-context (vec (concat (:role-messages node) (:task-messages node)))]
          (reset! current-node node-id)
          (flow/inject flow flow-in-coord [(frame/scenario-context-update {:messages append-context
                                                                           :tools tools})])))
      (start [s]
        (when-not @initialized?
          (reset! initialized? true)
          (set-node s (:initial-node scenario-config)))))))

(me/humanize
  (m/explain ScenarioConfig
             {:initial-node :hello
              :nodes
              {:start
               {:role-messages [{:role :system
                                 :content "You are a restaurant reservation assistant for La Maison, an upscale French restaurant. You must ALWAYS use one of the available functions to progress the conversation. This is a phone conversations and your responses will be converted to audio. Avoid outputting special characters and emojis. Be casual and friendly."}]
                :task-messages [{:role :system
                                 :content "Warmly greet the customer and ask how many people are in their party."}]
                :functions [{:type :function
                             :function
                             {:name "record_party_size"
                              :handler (fn [{:keys [size]}] size)
                              :description "Record the number of people in the party"
                              :parameters
                              {:type :object
                               :properties
                               {:size {:type :integer
                                       :description "Number of people that will dine."
                                       :minimum 1
                                       :maximum 12}}
                               :required [:size]}
                              :transition-to :get-timeee}}]}
               :get-time
               {:task-messages [{:role :system
                                 :content "Ask what time they'd like to dine. Restaurant is open 5 PM to 10 PM. After they provide a time, confirm it's within operating hours before recording. Use 24-hour format for internal recording (e.g., 17:00 for 5 PM)."}]
                :functions [{:type :function
                             :function {:name "record_time"
                                        :handler (fn [{:keys [time]}] time)
                                        :description "Record the requested time"
                                        :parameters {:type :object
                                                     :properties {:time {:type :string
                                                                         :pattern "^(17|18|19|20|21|22):([0-5][0-9])$"
                                                                         :description "Reservation time in 24-hour format (17:00-22:00)"}}
                                                     :required [:time]}
                                        :transition_to "confirm"}}]}}}))

(comment
  (scenario-manager
    {:flow (flow/create-flow {:procs {}
                              :conns []})
     :scenario
     {:initial-node :start
      :nodes
      {:start
       {:role-messages [{:role :system
                         :content "You are a restaurant reservation assistant for La Maison, an upscale French restaurant. You must ALWAYS use one of the available functions to progress the conversation. This is a phone conversations and your responses will be converted to audio. Avoid outputting special characters and emojis. Be casual and friendly."}]
        :task-messages [{:role :system
                         :content "Warmly greet the customer and ask how many people are in their party."}]
        :functions [{:type :function
                     :function
                     {:name "record_party_size"
                      :handler (fn [{:keys [size]}] ...)
                      :description "Record the number of people in the party"
                      :parameters
                      {:type :object
                       :properties
                       {:size {:type :integer
                               :minimum 1
                               :maximum 12}}
                       :required [:size]}
                      :transition-to :get-time}}]}
       :get-time
       {:task-messages [{:role :system
                         :content "Ask what time they'd like to dine. Restaurant is open 5 PM to 10 PM. After they provide a time, confirm it's within operating hours before recording. Use 24-hour format for internal recording (e.g., 17:00 for 5 PM)."}]
        :functions [{:type :function
                     :function {:name "record_time"
                                :handler (fn [{:keys [time]}] ...)
                                :description "Record the requested time"
                                :parameters {:type :object
                                             :properties {:time {:type :string
                                                                 :pattern "^(17|18|19|20|21|22):([0-5][0-9])$"
                                                                 :description "Reservation time in 24-hour format (17:00-22:00)"}}
                                             :required [:time]}
                                :transition_to "confirm"}}]}}}}))

(comment
  (me/humanize (m/explain schema/LLMTransitionToolDefinition {:type :function
                                                              :function
                                                              {:name "record_party_size"
                                                               :handler (fn [] :1)
                                                               :description "Record the number of people in the party"
                                                               :parameters
                                                               {:type :object
                                                                :properties
                                                                {:size {:type :integer
                                                                        :min 1
                                                                        :max 12
                                                                        :description "The people that want to dine"}}
                                                                :required [:size]}
                                                               :transition-to :get-time}})))
