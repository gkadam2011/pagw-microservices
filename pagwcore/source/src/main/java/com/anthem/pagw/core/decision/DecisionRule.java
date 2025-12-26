package com.anthem.pagw.core.decision;

/**
 * Interface for pluggable decision rules.
 * Allows custom business logic to be injected into the decision engine.
 */
public interface DecisionRule {
    
    /**
     * Get the unique name of this rule.
     */
    String getName();
    
    /**
     * Get the rule priority (higher = evaluated first).
     */
    default int getPriority() {
        return 0;
    }
    
    /**
     * Check if this rule should be evaluated for the given request.
     */
    boolean isApplicable(DecisionRequest request);
    
    /**
     * Evaluate the rule and return a decision (or null to continue with next rule).
     */
    DecisionResponse evaluate(DecisionRequest request);
}
