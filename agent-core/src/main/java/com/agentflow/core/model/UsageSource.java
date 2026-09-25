package com.agentflow.core.model;

/** Origin of usage counters; unknown counters must not be treated as reported cost. */
public enum UsageSource {
    REPORTED,
    FIXTURE,
    UNKNOWN
}
