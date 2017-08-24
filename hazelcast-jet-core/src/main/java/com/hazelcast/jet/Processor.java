/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet;

import com.hazelcast.logging.ILogger;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

/**
 * Does the computation needed to transform zero or more input data streams
 * into zero or more output streams. Each input/output stream corresponds
 * to one edge on the vertex represented by this processor. The
 * correspondence between a stream and an edge is established via the
 * edge's <em>ordinal</em>.
 * <p>
 * The special case of zero input streams applies to a <em>source</em>
 * vertex, which gets its data from the environment. The special case of
 * zero output streams applies to a <em>sink</em> vertex, which pushes its
 * data to the environment.
 * <p>
 * The processor accepts input from instances of {@link Inbox} and pushes
 * its output to an instance of {@link Outbox}.
 * <p>
 * By default the processor declares itself as "cooperative" ({@link
 * #isCooperative()} returns {@code true}). It will be assigned an outbox
 * of finite capacity which is not emptied until the processor yields back
 * to the execution engine. As soon as the outbox refuses an offered item,
 * the processor should save its current state and return to the caller.
 * It should also limit the amount of time it spends per call because it
 * will participate in a cooperative multithreading scheme, sharing a
 * thread with other processors.
 * <p>
 * On the other hand, if the processor declares itself as "non-cooperative"
 * ({@link #isCooperative()} returns {@code false}), then each item it
 * emits to the outbox will be immediately pushed into the outbound edge's
 * queue, blocking as needed until the queue accepts it. Therefore there is
 * no limit on the number of items that can be emitted during a single
 * processor call, and there is no limit on the time spent per call. For
 * example, a source processor can do all of its work in a single
 * invocation of {@link Processor#complete() complete()}, even if the stream
 * it generates is infinite.
 * <p>
 * Jet prefers cooperative processors because they result in greater overall
 * throughput. A processor should be non-cooperative only if it involves
 * blocking operations, which would cause all other processors on the same
 * shared thread to starve.
 */
public interface Processor {

    /**
     * Initializes this processor with the outbox that the processing methods
     * must use to deposit their output items. This method will be called
     * exactly once and strictly before any calls to processing methods ({@link
     * #process(int, Inbox)} and {@link #complete()}).
     * <p>
     * The default implementation does nothing.
     */
    default void init(@Nonnull Outbox outbox, @Nonnull Context context) {
    }

    /**
     * Called with a batch of items retrieved from an inbound edge's stream. The
     * items are in the inbox and this method may process zero or more of them,
     * removing each item after it is processed. Does not remove an item until it
     * is done with it.
     * <p>
     * No other methods are called until all items in the inbox are processed.
     * <p>
     * The default implementation does nothing.
     *
     * @param ordinal ordinal of the inbound edge
     * @param inbox   the inbox containing the pending items
     */
    default void process(int ordinal, @Nonnull Inbox inbox) {
    }

    /**
     * Called after the edge input with the supplied {@code ordinal} is exhausted. If
     * it returns {@code false}, it will be invoked again until it returns {@code true},
     * and until it does, no other methods will be invoked on the processor.
     *
     * @return {@code true} if the processor is now done completing this input,
     * {@code false} otherwise.
     */
    default boolean completeEdge(int ordinal) {
        return true;
    }

    /**
     * Called when there is no pending data in the inbox. Allows the processor
     * to produce output in the absence of input. If it returns {@code false},
     * it will be called again before proceeding to call any other method.
     * <p>
     * <strong>NOTE:</strong> a processor that declares itself {@link
     * #isCooperative() non-cooperative} must strictly return {@code true} from
     * this method.
     */
    default boolean tryProcess() {
        return true;
    }

    /**
     * Called after all the inbound edges' streams are exhausted. If it returns
     * {@code false}, it will be invoked again until it returns {@code true}.
     * After this method is called, no other processing methods will be called on
     * this processor, except for {@link #saveSnapshot()}.
     * <p>
     * Non-cooperative processors are required to return from this method from
     * time to time to give chance to check for new snapshots initiated or job
     * canceled. The time they spend in this method adds to latency of
     * snapshots and job cancellations.
     *
     * @return {@code true} if the completing step is now done, {@code false}
     *         otherwise.
     */
    default boolean complete() {
        return true;
    }

    /**
     * Tells whether this processor is able to participate in cooperative
     * multithreading. This means that each invocation of a processing method
     * will take a reasonably small amount of time (up to a millisecond).
     * Violations will manifest themselves as increased latency due to slower
     * switching of processors.
     * <p>
     * A cooperative processor should also not attempt any blocking operations,
     * such as I/O operations, waiting for locks/semaphores or sleep
     * operations. Violations to this rule will manifest themselves as less
     * than 100% CPU usage under maximum load.
     * <p>
     * If this processor declares itself cooperative, it will get a
     * non-blocking, buffering outbox of limited capacity and share a thread
     * with other cooperative processors. Otherwise it will get an
     * auto-flushing, blocking outbox and run in a dedicated Java thread.
     * <p>
     * Processor instances on single vertex are allowed to return different
     * value, but single processor instance must return constant value.
     * <p>
     * The default implementation returns {@code true}.
     */
    default boolean isCooperative() {
        return true;
    }

    /**
     * Store the state to the snapshot. Return {@code true} if done, or {@code
     * false} if the method should be called again. Method is allowed to add
     * items to outbox during this call.
     * <p>
     * The default implementation always returns {@code true}.
     * <p>
     * The method will never be called, if the inbox is not empty after the
     * {@link Processor#process(int, Inbox)} method returns.
     * After the inbox is done (this includes source processors), the method
     * can be called anytime between {@link Processor#complete()} calls. If a
     * processor never returns from {@link Processor#complete()} (which is
     * allowed for non-cooperative processors), method will never be called.
     * <p>
     * Snapshot method will always be called on the same thread as other
     * processing methods, so no synchronization is necessary.
     * <p>
     * If the processor {@link Processor#isCooperative() is cooperative}, this
     * method must also be cooperative.
     * <p>
     * If {@code false} is returned, the method will be called again before any
     * other methods are called.
     * <p>
     * After {@link Processor#complete()} returned {@code true}, this method
     * won't be called anymore.
     */
    default boolean saveSnapshot() {
        return true;
    }

    /**
     * Restore processor's state from an inbox with snapshotted state. Items
     * are of type {@code Map.Entry<Object, Object>}. The inbox contains just
     * one batch of items, method will be called multiple times if needed. If
     * there is no snapshot to restore, method won't be called at all, even
     * though the processors is stateful.
     * <p>
     * Processor is allowed to put items to Outbox during this call.
     * <p>
     * If the processor {@link Processor#isCooperative() is cooperative}, this
     * method must also be cooperative.
     */
    default void restoreSnapshot(@Nonnull Inbox inbox) {
        throw new JetException("Processor " + getClass().getName() + " does not override the restoreSnapshot() method");
    }

    /**
     * Called after all keys have been restored using {@link
     * #restoreSnapshot(Inbox)}.
     */
    default boolean finishSnapshotRestore() {
        return true;
    }

    /**
     * Context passed to the processor in the
     * {@link #init(Outbox, Processor.Context) init()} call.
     */
    interface Context {

        /**
         * Returns the current Jet instance
         */
        @Nonnull
        JetInstance jetInstance();

        /**
         *  Return a logger for the processor
         */
        @Nonnull
        ILogger logger();

        /**
         * Returns the index of the processor among all the processors created for
         * this vertex on all nodes: its unique cluster-wide index.
         */
        int globalProcessorIndex();

        /***
         * Returns the name of the vertex associated with this processor.
         */
        @Nonnull
        String vertexName();

        /**
         * Returns the future to check for cancellation status.
         * <p>
         * This is necessary, if the {@link #complete()} or
         * {@link #process(int, Inbox) process()} methods do not return promptly
         * after each blocking call (note, that blocking calls are allowed only
         * in {@link #isCooperative() non-cooperative} processors). In this case,
         * the methods should regularly check the {@code jobFuture}'s
         * {@link CompletableFuture#isDone() isDone()} and return, when it returns
         * {@code true}:
         *
         * <pre>
         * public boolean complete() {
         *     while (!jobFuture.isDone()) {
         *         // we should not block indefinitely, but rather with a timeout
         *         Collection data = blockingRead(timeout);
         *         for (Object item : data) {
         *             emit(item);
         *         }
         *     }
         * }
         * </pre>
         */
        @Nonnull
        CompletableFuture<Void> jobFuture();

        /**
         * Returns true, if snapshots will be saved for this job.
         */
        boolean snapshottingEnabled();
    }
}
