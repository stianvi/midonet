/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.sdn.flows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.midokura.netlink.NetlinkMessage.AttrKey;
import com.midokura.sdn.dp.Flow;
import com.midokura.sdn.dp.FlowMatch;
import com.midokura.sdn.dp.flows.FlowAction;
import com.midokura.sdn.dp.flows.FlowKey;

// not thread-safe
// TODO(pino): document this
// TODO(pino): implement eviction of wildcard flows
// TODO(pino): implement flow removal notification. Keep in mind that during
// TODO:       simulation, the match for the installed flow is not yet known.
public class FlowManager {

    /* The wildcard flow table is a map of wildcard match to wildcard flow
     * where the wildcard match itself is a map of FlowKeyAttr to FlowKey.
     */
    private class WildcardFlowTable extends
            HashMap<
                    Set<FlowKey<?>>,
                    WildcardFlow> {}

    /* The FlowManager needs one WildcardFlowTable for every wildcard pattern.
     * The wildcardTables structure maps wildcard pattern to WildcardFlowTable.
     * Each wildcard pattern is the set of FlowKeyAttr that must be matched
     * exactly.
     */
    private Map<Set<AttrKey<?>>, WildcardFlowTable> wildcardTables =
            new HashMap<Set<AttrKey<?>>, WildcardFlowTable>();

    /* The datapath flow table is a map of datapath FlowMatch to a list of
     * FlowActions. The wildcard flow table is a LinkedHashMap so that we
     * can use iterate it in insertion order (e.g. to find flows that are
     * candidates for eviction.
     */
    private LinkedHashMap<FlowMatch, List<FlowAction<?>>> dpFlowTable =
            new LinkedHashMap<FlowMatch, List<FlowAction<?>>>();

    /* Map each datapath flow back to its wildcard flow */
    private Map<FlowMatch, WildcardFlow> dpFlowToWildFlow =
            new HashMap<FlowMatch, WildcardFlow>();
    /* Map each wildcard flow to the set of datapath flows it generated. */
    private Map<WildcardFlow, Set<FlowMatch>> wildFlowToDpFlows =
            new HashMap<WildcardFlow, Set<FlowMatch>>();

    public int getNumDpFlows() {
        return dpFlowTable.size();
    }

    public List<FlowMatch> removeOldestDpFlows(int numToRemove) {
        if (numToRemove <= 0)
            throw new IllegalArgumentException("numToRemove must be positive");
        List<FlowMatch> removed = new ArrayList<FlowMatch>(numToRemove);
        Iterator<FlowMatch> it = dpFlowTable.keySet().iterator();
        while (removed.size() < numToRemove && it.hasNext()) {
            removed.add(it.next());
            it.remove();
        }
        return removed;
    }

    /**
     * Add a new wildcard flow.
     * @param wildFlow
     * @return True iff the wildcard flow was added. The flow will not be
     * added if the table already contains a wildcard flow with the same match.
     */
    public boolean add(WildcardFlow wildFlow) {
        // Get the WildcardFlowTable for this wild flow's pattern.
        Set<AttrKey<?>> pattern = getRequiredAttrKeys(wildFlow);
        WildcardFlowTable wildTable = wildcardTables.get(pattern);
        if (null == wildTable) {
            wildTable = new WildcardFlowTable();
            wildcardTables.put(pattern, wildTable);
        }
        if (!wildTable.containsKey(wildFlow.match)) {
            wildTable.put(wildFlow.match, wildFlow);
            wildFlowToDpFlows.put(wildFlow, new HashSet<FlowMatch>());
            return true;
        }
        return false;
    }

    private Set<AttrKey<?>> getRequiredAttrKeys(WildcardFlow wildFlow) {
        Set<AttrKey<?>> attrKeys = new HashSet<AttrKey<?>>();
        for (FlowKey<?> key : wildFlow.getMatch())
            attrKeys.add(key.getKey());
        return attrKeys;
    }

    /**
     * Add a new datapath flow and associate it to an existing wildcard flow
     * and its actions. The wildcard flow must have previously been successfully
     * added or the behavior is undefined.
     *
     * @param wildFlow
     * @param flowMatch
     * @return True iff both the datapath flow was added. The flow will not be
     * added if the table already contains a datapath flow with the same match.
     */
    public boolean add(FlowMatch flowMatch, WildcardFlow wildFlow) {
        if (dpFlowTable.containsKey(flowMatch))
            return false;
        dpFlowTable.put(flowMatch, wildFlow.actions);
        dpFlowToWildFlow.put(flowMatch, wildFlow);
        wildFlowToDpFlows.get(wildFlow).add(flowMatch);
        return true;
    }

    /**
     * Remove a wildcard flow and its associated datapath flows.  The wildcard
     * flow must have previously been successfully added or the behavior is
     * undefined.
     *
     * @param wildFlow
     * @return the set of datapath flows that was generated by this wild flow.
     */
    public Set<FlowMatch> remove(WildcardFlow wildFlow) {
        Set<FlowMatch> removedDpFlows = wildFlowToDpFlows.remove(wildFlow);
        for (FlowMatch flowMatch : removedDpFlows) {
            dpFlowToWildFlow.remove(flowMatch);
            dpFlowTable.remove(flowMatch);
        }
        // Get the WildcardFlowTable for this wild flow's pattern and remove
        // the wild flow.
        wildcardTables.get(getRequiredAttrKeys(wildFlow))
                .remove(wildFlow.match);
        return removedDpFlows;
    }

    /**
     * If a datapth flow matching this FlowMatch was already computed, return
     * its actions. Else null.
     * @param flowMatch
     * @return
     */
    public List<FlowAction<?>> getActionsForDpFlow(FlowMatch flowMatch) {
        return dpFlowTable.get(flowMatch);
    }

    /**
     * If the datapath FlowMatch matches a wildcard flow, create the datapath
     * Flow. If the FlowMatch matches multiple wildcard flows, the FlowManager
     * will arbitrarily choose one that has the highest priority (lowest
     * priority value). If the FlowManager already contains a datapath flow
     * for this FlowMatch, it will return that instead.
     * @param flowMatch
     * @return A datapath Flow if one already exists or a new one if the
     * FlowMatch matches a wildcard flow. Otherwise, null.
     */
    public Flow createDpFlow(FlowMatch flowMatch) {
        List<FlowAction<?>> actions = dpFlowTable.get(flowMatch);
        if (null != actions)
            return new Flow().setMatch(flowMatch).setActions(actions);
        // Iterate through the WildcardFlowTables to find candidate wild flows.
        WildcardFlow wildcardFlow = null;
        for (Map.Entry<Set<AttrKey<?>>, WildcardFlowTable> wTableEntry :
            wildcardTables.entrySet()) {
            WildcardFlowTable table = wTableEntry.getValue();
            Set<AttrKey<?>> pattern = wTableEntry.getKey();
            Set<FlowKey<?>> wildMatch =
                    getWildMatchForDpFlow(flowMatch, pattern);
            if (null != wildMatch) {
                WildcardFlow candidate = table.get(wildMatch);
                if (null != candidate &&
                        candidate.priority < wildcardFlow.priority)
                    wildcardFlow = candidate;
            }
        }
        if (null != wildcardFlow) {
            Flow dpFlow = new Flow().setMatch(flowMatch)
                    .setActions(wildcardFlow.getActions());
            dpFlowTable.put(flowMatch, wildcardFlow.getActions());
            dpFlowToWildFlow.put(flowMatch, wildcardFlow);
            wildFlowToDpFlows.get(wildcardFlow).add(flowMatch);
            return dpFlow;
        }
        else {
            return null;
        }
    }

    /**
     * Make a wildcard match with FlowKeys from the given FlowMatch. The
     * wildcard match should have all the keys required by the 'requiredKeys'
     * set/pattern.
     *
     * @param flowMatch
     * @param requiredKeys
     * @return A wildcard match (map of FlowKeyAttrs to FlowKeys) if the
     * FlowMatch has all the FlowKeys specified in the 'requiredKeys' set.
     * Otherwise, null.
     */
    private Set<FlowKey<?>> getWildMatchForDpFlow(
            FlowMatch flowMatch, Set<AttrKey<?>> requiredKeys) {
        Set<AttrKey<?>> foundKeys = new HashSet<AttrKey<?>>();
        Set<FlowKey<?>> wildMatch = new HashSet<FlowKey<?>>();
        for (FlowKey<?> key : flowMatch.getKeys()) {
            if (requiredKeys.contains(key.getKey())) {
                foundKeys.add(key.getKey());
                wildMatch.add(key);
            }
        }
        return (foundKeys.equals(requiredKeys))? wildMatch : null;
    }

}
