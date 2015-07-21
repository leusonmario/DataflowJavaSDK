/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.dataflow.sdk.util;

import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.transforms.windowing.Trigger;
import com.google.cloud.dataflow.sdk.transforms.windowing.Trigger.MergingTriggerInfo;
import com.google.cloud.dataflow.sdk.transforms.windowing.Trigger.Timers;
import com.google.cloud.dataflow.sdk.transforms.windowing.Trigger.TriggerInfo;
import com.google.cloud.dataflow.sdk.util.ReduceFn.MergingStateContext;
import com.google.cloud.dataflow.sdk.util.ReduceFn.StateContext;
import com.google.cloud.dataflow.sdk.util.ReduceFnContextFactory.MergingStateContextImpl;
import com.google.cloud.dataflow.sdk.util.ReduceFnContextFactory.StateContextImpl;
import com.google.cloud.dataflow.sdk.util.TimerManager.TimeDomain;
import com.google.cloud.dataflow.sdk.util.state.StateInternals;
import com.google.cloud.dataflow.sdk.util.state.StateNamespace;
import com.google.cloud.dataflow.sdk.util.state.StateNamespaces;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import org.joda.time.Instant;

import java.io.IOException;
import java.util.BitSet;
import java.util.Collection;
import java.util.Map;

/**
 * Factory for creating instances of the various {@link Trigger} contexts.
 */
class TriggerContextFactory<W extends BoundedWindow> {

  private TimerManager timerManager;

  private final WindowingStrategy<?, W> windowingStrategy;
  private StateInternals stateInternals;
  private ActiveWindowSet<W> activeWindows;

  TriggerContextFactory(TimerManager timerManager, WindowingStrategy<?, W> windowingStrategy,
      StateInternals stateInternals, ActiveWindowSet<W> activeWindows) {
    this.timerManager = timerManager;
    this.windowingStrategy = windowingStrategy;
    this.stateInternals = stateInternals;
    this.activeWindows = activeWindows;
  }

  public Trigger<W>.TriggerContext base(
      ReduceFn<?, ?, ?, W>.Context context, ExecutableTrigger<W> rootTrigger, BitSet finishedSet) {
    return new TriggerContextImpl(context.window(), rootTrigger, finishedSet);
  }

  public Trigger<W>.OnElementContext create(
      ReduceFn<?, ?, ?, W>.ProcessValueContext context,
      ExecutableTrigger<W> rootTrigger, BitSet finishedSet) {
    return new OnElementContextImpl(
        context.window(), rootTrigger, finishedSet,
        context.value(), context.timestamp());
  }

  public Trigger<W>.OnTimerContext create(
      ReduceFn<?, ?, ?, W>.Context context,
      ExecutableTrigger<W> rootTrigger, BitSet finishedSet,
      int destinationIndex) {
    return new OnTimerContextImpl(context.window(), rootTrigger, finishedSet, destinationIndex);
  }

  public Trigger<W>.OnMergeContext create(
      ReduceFn<?, ?, ?, W>.OnMergeContext context,
      ExecutableTrigger<W> rootTrigger, BitSet finishedSet,
      Map<W, BitSet> finishedSets) {
    return new OnMergeContextImpl(context.window(), rootTrigger, finishedSet,
        context.mergingWindows(), finishedSets);
  }

  private class TriggerInfoImpl implements Trigger.TriggerInfo<W> {

    protected final ExecutableTrigger<W> trigger;
    protected final BitSet finishedSet;
    private final Trigger<W>.TriggerContext context;

    public TriggerInfoImpl(
        ExecutableTrigger<W> trigger, BitSet finishedSet, Trigger<W>.TriggerContext context) {
      this.trigger = trigger;
      this.finishedSet = finishedSet;
      this.context = context;
    }

    @Override
    public boolean isMerging() {
      return !windowingStrategy.getWindowFn().isNonMerging();
    }

    @Override
    public Iterable<ExecutableTrigger<W>> subTriggers() {
      return trigger.subTriggers();
    }

    @Override
    public ExecutableTrigger<W> subTrigger(int subtriggerIndex) {
      return trigger.subTriggers().get(subtriggerIndex);
    }

    @Override
    public boolean isFinished() {
      return finishedSet.get(trigger.getTriggerIndex());
    }

    @Override
    public boolean areAllSubtriggersFinished() {
      return Iterables.isEmpty(unfinishedSubTriggers());
    }

    @Override
    public Iterable<ExecutableTrigger<W>> unfinishedSubTriggers() {
      return FluentIterable
          .from(trigger.subTriggers())
          .filter(new Predicate<ExecutableTrigger<W>>() {
            @Override
            public boolean apply(ExecutableTrigger<W> input) {
              return !finishedSet.get(input.getTriggerIndex());
            }
          });
    }

    @Override
    public ExecutableTrigger<W> firstUnfinishedSubTrigger() {
      for (ExecutableTrigger<W> subTrigger : trigger.subTriggers()) {
        if (!finishedSet.get(subTrigger.getTriggerIndex())) {
          return subTrigger;
        }
      }
      return null;
    }

    @Override
    public void resetTree() throws Exception {
      finishedSet.clear(trigger.getTriggerIndex(), trigger.getFirstIndexAfterSubtree());
      trigger.invokeClear(context);
    }

    @Override
    public void setFinished(boolean finished) {
      finishedSet.set(trigger.getTriggerIndex(), finished);
    }
  }

  private class MergingTriggerInfoImpl
      extends TriggerInfoImpl implements Trigger.MergingTriggerInfo<W> {

    private final Map<W, BitSet> finishedSets;

    public MergingTriggerInfoImpl(
        ExecutableTrigger<W> trigger,
        BitSet finishedSet,
        Trigger<W>.TriggerContext context,
        Map<W, BitSet> finishedSets) {
      super(trigger, finishedSet, context);
      this.finishedSets = finishedSets;
    }

    @Override
    public boolean finishedInAnyMergingWindow() {
      for (BitSet bitSet : finishedSets.values()) {
        if (bitSet.get(trigger.getTriggerIndex())) {
          return true;
        }
      }
      return false;
    }

    @Override
    public Iterable<W> getFinishedMergingWindows() {
      return Maps.filterValues(finishedSets, new Predicate<BitSet>() {
        @Override
        public boolean apply(BitSet input) {
          return input.get(trigger.getTriggerIndex());
        }
      }).keySet();
    }
  }

  private class TimersImpl implements Trigger.Timers {

    private final StateNamespace namespace;

    public TimersImpl(StateNamespace namespace) {
      this.namespace = namespace;
    }

    @Override
    public void setTimer(Instant timestamp, TimeDomain timeDomain) throws IOException {
      timerManager.setTimer(namespace, timestamp, timeDomain);
    }

    @Override
    public void deleteTimer(TimeDomain timeDomain) throws IOException {
      timerManager.deleteTimer(namespace, timeDomain);
    }

    @Override
    public Instant currentProcessingTime() {
      return timerManager.currentProcessingTime();
    }
  }

  private StateContextImpl<W> triggerState(W window, ExecutableTrigger<W> trigger) {
    return new TriggerStateContextImpl<W>(
        activeWindows, windowingStrategy.getWindowFn().windowCoder(),
        stateInternals, window, trigger);
  }

  private class TriggerStateContextImpl<W extends BoundedWindow> extends StateContextImpl<W> {

    private int triggerIndex;

    public TriggerStateContextImpl(ActiveWindowSet<W> activeWindows,
        Coder<W> windowCoder, StateInternals stateInternals, W window,
        ExecutableTrigger<W> trigger) {
      super(activeWindows, windowCoder, stateInternals, window);
      this.triggerIndex = trigger.getTriggerIndex();

      // Annoyingly, since we hadn't set the triggerIndex yet (we can't do it before super)
      // This will would otherwise have incorporated 0 as the trigger index.
      this.namespace = namespaceFor(window);
    }

    @Override
    protected StateNamespace namespaceFor(W window) {
      return StateNamespaces.windowAndTrigger(windowCoder, window, triggerIndex);
    }
  }

  private class TriggerContextImpl extends Trigger<W>.TriggerContext {

    private final StateContextImpl<W> state;
    private final TimersImpl timers;
    private final TriggerInfoImpl triggerInfo;

    private TriggerContextImpl(
        W window,
        ExecutableTrigger<W> trigger,
        BitSet finishedSet) {
      trigger.getSpec().super();
      this.state = triggerState(window, trigger);
      this.timers = new TimersImpl(state.namespace());
      this.triggerInfo = new TriggerInfoImpl(trigger, finishedSet, this);
    }

    @Override
    public Trigger<W>.TriggerContext forTrigger(ExecutableTrigger<W> trigger) {
      return new TriggerContextImpl(state.window(), trigger, triggerInfo.finishedSet);
    }

    @Override
    public TriggerInfo<W> trigger() {
      return triggerInfo;
    }

    @Override
    public StateContext state() {
      return state;
    }

    @Override
    public W window() {
      return state.window();
    }

    @Override
    public Timers timers() {
      return timers;
    }
  }


  private class OnElementContextImpl extends Trigger<W>.OnElementContext {

    private final StateContextImpl<W> state;
    private final TimersImpl timers;
    private final TriggerInfoImpl triggerInfo;
    private final Object element;
    private final Instant eventTimestamp;

    private OnElementContextImpl(
        W window,
        ExecutableTrigger<W> trigger,
        BitSet finishedSet,
        Object element,
        Instant eventTimestamp) {
      trigger.getSpec().super();
      this.state = triggerState(window, trigger);
      this.timers = new TimersImpl(state.namespace());
      this.triggerInfo = new TriggerInfoImpl(trigger, finishedSet, this);
      this.element = element;
      this.eventTimestamp = eventTimestamp;
    }

    @Override
    public Object element() {
      return element;
    }

    @Override
    public Instant eventTimestamp() {
      return eventTimestamp;
    }

    @Override
    public Trigger<W>.OnElementContext forTrigger(ExecutableTrigger<W> trigger) {
      return new OnElementContextImpl(
          state.window(), trigger, triggerInfo.finishedSet, element, eventTimestamp);
    }

    @Override
    public TriggerInfo<W> trigger() {
      return triggerInfo;
    }

    @Override
    public StateContext state() {
      return state;
    }

    @Override
    public W window() {
      return state.window();
    }

    @Override
    public Timers timers() {
      return timers;
    }
  }

  private class OnTimerContextImpl extends Trigger<W>.OnTimerContext {

    private final StateContextImpl<W> state;
    private final TimersImpl timers;
    private final TriggerInfoImpl triggerInfo;
    private final int destinationIndex;

    private OnTimerContextImpl(
        W window,
        ExecutableTrigger<W> trigger,
        BitSet finishedSet,
        int destinationIndex) {
      trigger.getSpec().super();
      this.state = triggerState(window, trigger);
      this.timers = new TimersImpl(state.namespace());
      this.triggerInfo = new TriggerInfoImpl(trigger, finishedSet, this);
      this.destinationIndex = destinationIndex;
    }

    @Override
    public Trigger<W>.OnTimerContext forTrigger(ExecutableTrigger<W> trigger) {
      return new OnTimerContextImpl(
          state.window(), trigger, triggerInfo.finishedSet, destinationIndex);
    }

    @Override
    public TriggerInfo<W> trigger() {
      return triggerInfo;
    }

    @Override
    public StateContext state() {
      return state;
    }

    @Override
    public W window() {
      return state.window();
    }

    @Override
    public Timers timers() {
      return timers;
    }

    @Override
    public ExecutableTrigger<W> nextStepTowardsDestination() {
      return triggerInfo.trigger.getSubTriggerContaining(destinationIndex);
    }

    @Override
    public boolean isDestination() {
      return triggerInfo.trigger.getTriggerIndex() == destinationIndex;
    }
  }

  private class OnMergeContextImpl extends Trigger<W>.OnMergeContext {

    private final MergingStateContextImpl<W> state;
    private final TimersImpl timers;
    private final MergingTriggerInfoImpl triggerInfo;

    private OnMergeContextImpl(
        W window,
        ExecutableTrigger<W> trigger,
        BitSet finishedSet,
        Collection<W> mergingWindows,
        Map<W, BitSet> finishedSets) {
      trigger.getSpec().super();
      this.state = new MergingStateContextImpl<>(triggerState(window, trigger), mergingWindows);
      this.timers = new TimersImpl(state.namespace());
      this.triggerInfo = new MergingTriggerInfoImpl(trigger, finishedSet, this, finishedSets);
    }

    @Override
    public Trigger<W>.OnMergeContext forTrigger(ExecutableTrigger<W> trigger) {
      return new OnMergeContextImpl(
          state.window(), trigger, triggerInfo.finishedSet,
          state.mergingWindows(), triggerInfo.finishedSets);
    }

    @Override
    public Iterable<W> oldWindows() {
      return state.mergingWindows();
    }

    @Override
    public MergingStateContext state() {
      return state;
    }

    @Override
    public MergingTriggerInfo<W> trigger() {
      return triggerInfo;
    }

    @Override
    public W window() {
      return state.window();
    }

    @Override
    public Timers timers() {
      return timers;
    }
  }
}