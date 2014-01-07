/*
// Licensed to Julian Hyde under one or more contributor license
// agreements. See the NOTICE file distributed with this work for
// additional information regarding copyright ownership.
//
// Julian Hyde licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except in
// compliance with the License. You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
*/
package org.eigenbase.relopt.volcano;

import java.util.*;
import java.util.logging.*;

import org.eigenbase.rel.*;
import org.eigenbase.relopt.*;
import org.eigenbase.util.*;

/**
 * <code>VolcanoRuleCall</code> implements the {@link RelOptRuleCall} interface
 * for VolcanoPlanner.
 */
public class VolcanoRuleCall extends RelOptRuleCall {
  //~ Instance fields --------------------------------------------------------

  protected final VolcanoPlanner volcanoPlanner;

  /**
   * List of {@link RelNode} generated by this call. For debugging purposes.
   */
  private List<RelNode> generatedRelList;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a rule call, internal, with array to hold bindings.
   *
   * @param planner Planner
   * @param operand First operand of the rule
   * @param rels    Array which will hold the matched relational expressions
   */
  protected VolcanoRuleCall(
      VolcanoPlanner planner,
      RelOptRuleOperand operand,
      RelNode[] rels) {
    super(
        planner,
        operand,
        rels,
        Collections.<RelNode, List<RelNode>>emptyMap());
    this.volcanoPlanner = planner;
  }

  /**
   * Creates a rule call.
   *
   * @param planner Planner
   * @param operand First operand of the rule
   */
  VolcanoRuleCall(
      VolcanoPlanner planner,
      RelOptRuleOperand operand) {
    this(
        planner,
        operand,
        new RelNode[operand.getRule().operands.size()]);
  }

  //~ Methods ----------------------------------------------------------------

  // implement RelOptRuleCall
  public void transformTo(RelNode rel, Map<RelNode, RelNode> equiv) {
    if (tracer.isLoggable(Level.FINE)) {
      tracer.fine(
          "Transform to: rel#" + rel.getId() + " via " + getRule()
              + (equiv.isEmpty() ? "" : " with equivalences " + equiv));
      if (generatedRelList != null) {
        generatedRelList.add(rel);
      }
    }
    try {
      // It's possible that rel is a subset or is already registered.
      // Is there still a point in continuing? Yes, because we might
      // discover that two sets of expressions are actually equivalent.

      // Make sure traits that the new rel doesn't know about are
      // propagated.
      RelTraitSet rels0Traits = rels[0].getTraitSet();
      new RelTraitPropagationVisitor(
          getPlanner(),
          rels0Traits).go(rel);

      if (tracer.isLoggable(Level.FINEST)) {
        // Cannot call RelNode.toString() yet, because rel has not
        // been registered. For now, let's make up something similar.
        String relDesc =
            "rel#" + rel.getId() + ":" + rel.getRelTypeName();
        tracer.finest(
            "call#" + id
            + ": Rule " + getRule() + " arguments "
            + Arrays.toString(rels) + " created " + relDesc);
      }

      if (volcanoPlanner.listener != null) {
        RelOptListener.RuleProductionEvent event =
            new RelOptListener.RuleProductionEvent(
                volcanoPlanner,
                rel,
                this,
                true);
        volcanoPlanner.listener.ruleProductionSucceeded(event);
      }

      // Registering the root relational expression implicitly registers
      // its descendants. Register any explicit equivalences first, so we
      // don't register twice and cause churn.
      for (Map.Entry<RelNode, RelNode> entry : equiv.entrySet()) {
        volcanoPlanner.ensureRegistered(
            entry.getKey(), entry.getValue(), this);
      }
      volcanoPlanner.ensureRegistered(rel, rels[0], this);

      if (volcanoPlanner.listener != null) {
        RelOptListener.RuleProductionEvent event =
            new RelOptListener.RuleProductionEvent(
                volcanoPlanner,
                rel,
                this,
                false);
        volcanoPlanner.listener.ruleProductionSucceeded(event);
      }
    } catch (Throwable e) {
      throw Util.newInternal(
          e,
          "Error occurred while applying rule " + getRule());
    }
  }

  /**
   * Called when all operands have matched.
   *
   * @pre getRule().matches(this)
   */
  protected void onMatch() {
    assert getRule().matches(this);
    volcanoPlanner.checkCancel();
    try {
      if (volcanoPlanner.isRuleExcluded(getRule())) {
        if (tracer.isLoggable(Level.FINE)) {
          tracer.fine(
              "Rule [" + getRule() + "] not fired"
                  + " due to exclusion filter");
        }
        return;
      }

      for (int i = 0; i < rels.length; i++) {
        RelNode rel = rels[i];
        RelSubset subset = volcanoPlanner.getSubset(rel);

        if (subset == null) {
          if (tracer.isLoggable(Level.FINE)) {
            tracer.fine(
                "Rule [" + getRule() + "] not fired because"
                    + " operand #" + i + " (" + rel
                    + ") has no subset");
          }
          return;
        }

        if (subset.set.equivalentSet != null) {
          if (tracer.isLoggable(Level.FINE)) {
            tracer.fine(
                "Rule [" + getRule() + "] not fired because"
                    + " operand #" + i + " (" + rel
                    + ") belongs to obsolete set");
          }
          return;
        }

        final Double importance =
            volcanoPlanner.relImportances.get(rel);
        if ((importance != null) && (importance == 0d)) {
          if (tracer.isLoggable(Level.FINE)) {
            tracer.fine(
                "Rule [" + getRule() + "] not fired because"
                    + " operand #" + i + " (" + rel
                    + ") has importance=0");
          }
          return;
        }
      }

      if (tracer.isLoggable(Level.FINE)) {
        tracer.fine(
            "call#" + id
            + ": Apply rule [" + getRule() + "] to "
            + Arrays.toString(rels));
      }

      if (volcanoPlanner.listener != null) {
        RelOptListener.RuleAttemptedEvent event =
            new RelOptListener.RuleAttemptedEvent(
                volcanoPlanner,
                rels[0],
                this,
                true);
        volcanoPlanner.listener.ruleAttempted(event);
      }

      if (tracer.isLoggable(Level.FINE)) {
        this.generatedRelList = new ArrayList<RelNode>();
      }

      getRule().onMatch(this);

      if (tracer.isLoggable(Level.FINE)) {
        if (generatedRelList.isEmpty()) {
          tracer.fine("call#" + id + " generated 0 successors.");
        } else {
          tracer.fine(
              "call#" + id + " generated " + generatedRelList.size()
                  + " successors: " + generatedRelList);
        }
        this.generatedRelList = null;
      }

      if (volcanoPlanner.listener != null) {
        RelOptListener.RuleAttemptedEvent event =
            new RelOptListener.RuleAttemptedEvent(
                volcanoPlanner,
                rels[0],
                this,
                false);
        volcanoPlanner.listener.ruleAttempted(event);
      }
    } catch (Throwable e) {
      throw Util.newInternal(
          e,
          "Error while applying rule "
              + getRule() + ", args " + Arrays.toString(rels));
    }
  }

  /**
   * Applies this rule, with a given relexp in the first slot.
   *
   * @pre operand0.matches(rel)
   */
  void match(RelNode rel) {
    assert (getOperand0().matches(rel));
    final int solve = 0;
    int operandOrdinal = getOperand0().solveOrder[solve];
    this.rels[operandOrdinal] = rel;
    matchRecurse(solve + 1);
  }

  /**
   * Recursively matches operands above a given solve order.
   *
   * @param solve Solver order of operand
   * @pre solve &gt; 0
   * @pre solve &lt;= rule.operands.length
   */
  private void matchRecurse(int solve) {
    if (solve == getRule().operands.size()) {
      // We have matched all operands. Now ask the rule whether it
      // matches; this gives the rule chance to apply side-conditions.
      // If the side-conditions are satisfied, we have a match.
      if (getRule().matches(this)) {
        onMatch();
      }
    } else {
      int operandOrdinal = getOperand0().solveOrder[solve];
      int previousOperandOrdinal = getOperand0().solveOrder[solve - 1];
      boolean ascending = operandOrdinal < previousOperandOrdinal;
      RelOptRuleOperand previousOperand =
          getRule().operands.get(previousOperandOrdinal);
      RelOptRuleOperand operand = getRule().operands.get(operandOrdinal);

      Collection<RelNode> successors;
      if (ascending) {
        assert (previousOperand.getParent() == operand);
        final RelNode childRel = rels[previousOperandOrdinal];
        RelSubset subset = volcanoPlanner.getSubset(childRel);
        successors = subset.getParentRels();
      } else {
        int parentOrdinal = operand.getParent().ordinalInRule;
        RelNode parentRel = rels[parentOrdinal];
        List<RelNode> inputs = parentRel.getInputs();
        if (operand.ordinalInParent < inputs.size()) {
          RelSubset subset =
              (RelSubset) inputs.get(operand.ordinalInParent);
          successors = subset.getRelList();
        } else {
          // The operand expects parentRel to have a certain number
          // of inputs and it does not.
          successors = Collections.emptyList();
        }
      }

      for (RelNode rel : successors) {
        if (!operand.matches(rel)) {
          continue;
        }
        if (ascending) {
          // We know that the previous operand was *a* child of
          // its parent, but now check that it is the *correct*
          // child
          final RelSubset input =
              (RelSubset) rel.getInput(
                  previousOperand.ordinalInParent);
          List<RelNode> inputRels = input.set.getRelsFromAllSubsets();
          if (!inputRels.contains(rels[previousOperandOrdinal])) {
            continue;
          }
        }
        rels[operandOrdinal] = rel;
        matchRecurse(solve + 1);
      }
    }
  }
}

// End VolcanoRuleCall.java
