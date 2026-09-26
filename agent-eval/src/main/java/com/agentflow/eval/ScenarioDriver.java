package com.agentflow.eval;

/** One freshly created scenario scope. Implementations own and close all fixture resources. */
public interface ScenarioDriver extends AutoCloseable {
    ObservedCase execute(EvalCase scenario, EvalVariant variant, int repeat) throws Exception;
    @Override void close() throws Exception;
}
