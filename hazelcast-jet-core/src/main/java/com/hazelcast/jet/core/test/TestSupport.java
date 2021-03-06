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

package com.hazelcast.jet.core.test;

import com.hazelcast.config.NetworkConfig;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.jet.core.test.TestOutbox.MockData;
import com.hazelcast.nio.Address;

import javax.annotation.Nonnull;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.hazelcast.jet.Util.entry;
import static com.hazelcast.jet.core.test.JetAssert.assertEquals;
import static com.hazelcast.jet.core.test.JetAssert.assertTrue;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * A utility to test processors. It will initialize the processor instance,
 * pass input items to it and assert the outbox contents.
 * <p>
 * This method does the following:
 * <ul>
 *     <li>initializes the processor by calling {@link Processor#init(
 *     com.hazelcast.jet.core.Outbox, com.hazelcast.jet.core.SnapshotOutbox,
 *     Processor.Context) Processor.init()}
 *
 *     <li>does snapshot+restore (optional, see below)
 *
 *     <li>calls {@link Processor#process(int, com.hazelcast.jet.core.Inbox)
 *     Processor.process(0, inbox)}, the inbox always contains one item
 *     from {@code input} parameter
 *
 *     <li>every time the inbox gets empty does snapshot+restore
 *
 *     <li>calls {@link Processor#complete()} until it returns {@code true}
 *     ({@link #disableCompleteCall() optional})
 *
 *     <li>does snapshot+restore after {@code complete()} returned {@code
 *     false}
 * </ul>
 * The {@link #disableSnapshots() optional} snapshot+restore test procedure:
 * <ul>
 *     <li>{@code saveToSnapshot()} is called
 *
 *     <li>new processor instance is created, from now on only this
 *     instance will be used
 *
 *     <li>snapshot is restored using {@code restoreFromSnapshot()}
 *
 *     <li>{@code finishSnapshotRestore()} is called
 * </ul>
 * <p>
 * For each call to any processing method the progress is asserted ({@link
 * #disableProgressAssertion() optional}). The processor must do at least one
 * of these:<ul>
 *     <li>take something from inbox
 *     <li>put something to outbox
 *     <li>for boolean-returning methods, returning {@code true} is
 *     considered as making progress
 * </ul>
 * <h4>Cooperative processors</h4>
 * For cooperative processors a 1-capacity outbox will be provided, which
 * will additionally be full in every other call to {@code process()}. This
 * will test the edge case: the {@code process()} method is called even
 * when the outbox is full to give the processor a chance to process inbox.
 * The snapshot outbox will also have capacity of 1 for a cooperative
 * processor.
 * <p>
 * Additionally, time spent in each call to processing method must not
 * exceed {@link #cooperativeTimeout(long)}.
 * <h4>Not-covered cases</h4>
 * This class does not cover these cases:<ul>
 *     <li>Testing of processors which distinguish input or output edges
 *     by ordinal
 *     <li>Checking that the state of a stateful processor is empty at the
 *     end (you can do that yourself afterwards with the last instance
 *     returned from your supplier).
 *     <li>This utility never calls {@link Processor#tryProcess()}.
 * </ul>
 *
 * <h4>Example usage</h4>
 * This will test one of the jet-provided processors:
 * <pre>{@code
 * TestSupport.verifyProcessor(Processors.map((String s) -> s.toUpperCase()))
 *         .input(asList("foo", "bar"))
 *         .expectOutput(asList("FOO", "BAR"));
 * }</pre>
 */
public final class TestSupport {

    private static final Address LOCAL_ADDRESS;

    // 1ms should be enough for a cooperative call. We warn, when it's more than 5ms and
    // fail when it's more than 100ms, possibly due to other  activity in the system, such as
    // parallel tests or GC.
    private static final long COOPERATIVE_TIME_LIMIT_MS_FAIL = 1000;
    private static final long COOPERATIVE_TIME_LIMIT_MS_WARN = 5;

    static {
        try {
            LOCAL_ADDRESS = new Address("localhost", NetworkConfig.DEFAULT_PORT);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    private Supplier<Processor> supplier;
    private List<?> input = emptyList();
    private List<?> expectedOutput = emptyList();
    private boolean assertProgress = true;
    private boolean doSnapshots = true;
    private boolean logInputOutput;
    private boolean callComplete = true;
    private long cooperativeTimeout = COOPERATIVE_TIME_LIMIT_MS_FAIL;
    private BiPredicate<? super List<?>, ? super List<?>> outputChecker = Objects::equals;

    private TestSupport(@Nonnull Supplier<Processor> supplier) {
        this.supplier = supplier;
    }

    /**
     * @param processor a processor instance to test. {@link #disableSnapshots()}
     *                  will be set to {@code false}, because can't have new instance after each
     *                  restore.
     */
    public static  TestSupport verifyProcessor(Processor processor) {
        return new TestSupport(singletonSupplier(processor))
                .disableSnapshots();
    }

    /**
     * @param supplier a processor supplier create processor instances
     */
    public static  TestSupport verifyProcessor(@Nonnull Supplier<Processor> supplier) {
        return new TestSupport(supplier);
    }

    /**
     * @param supplier a processor supplier create processor instances
     */
    public static  TestSupport verifyProcessor(@Nonnull ProcessorSupplier supplier) {
        return new TestSupport(supplierFrom(supplier));
    }

    /**
     * @param supplier a processor supplier create processor instances
     */
    public static  TestSupport verifyProcessor(@Nonnull ProcessorMetaSupplier supplier) {
        return new TestSupport(supplierFrom(supplier));
    }

    /**
     * Sets the input objects for processor.
     * <p>
     * Defaults to empty list.
     *
     * @return {@code this} instance for fluent API.
     */
    public TestSupport input(@Nonnull List<?> input) {
        this.input = input;
        return this;
    }

    /**
     * Sets the expected output and runs the test.
     *
     * @throws AssertionError If some assertion does not hold.
     */
    public void expectOutput(@Nonnull List<?> expectedOutput) {
        this.expectedOutput = expectedOutput;
        runTest(doSnapshots);
    }

    /**
     * Disables checking of progress of processing methods (see {@link
     * TestSupport class javadoc} for information on what is "progress").
     *
     * @return {@code this} instance for fluent API.
     */
    public TestSupport disableProgressAssertion() {
        this.assertProgress = false;
        return this;
    }

    /**
     * Disable snapshot save and restore before first item and after each
     * {@code process()} and {@code complete()} call.
     *
     * @return {@code this} instance for fluent API.
     */
    public TestSupport disableSnapshots() {
        this.doSnapshots = false;
        return this;
    }

    /**
     * Disables logging of input and output objects. Normally they are logged
     * as they are processed to standard output.
     *
     * @return {@code this} instance for fluent API.
     */
    public TestSupport disableLogging() {
        this.logInputOutput = false;
        return this;
    }

    /**
     * Disables calling {@code complete()} method during the test. Suitable for
     * testing of streaming processors to make sure that the flushing code in
     * {@code complete()} method is not executed.
     *
     * @return {@code this} instance for fluent API.
     */
    public TestSupport disableCompleteCall() {
        this.callComplete = false;
        return this;
    }

    /**
     * If {@code timeout > 0}, the test will fail if any call to processing
     * method in a cooperative processor exceeds this timeout. Has no effect
     * for non-cooperative processors.
     * <p>
     * Default value is {@link #COOPERATIVE_TIME_LIMIT_MS_FAIL} ms. Useful to
     * set to 0 during debugging.
     *
     * @return {@code this} instance for fluent API.
     */
    public TestSupport cooperativeTimeout(long timeout) {
        this.cooperativeTimeout = timeout;
        return this;
    }

    /**
     * Predicate to compare expected and actual output.
     * <p>
     * Defaults to {@code Objects::equals}
     *
     * @return {@code this} instance for fluent API.
     */
    public TestSupport outputChecker(@Nonnull BiPredicate<? super List<?>, ? super List<?>> outputChecker) {
        this.outputChecker = outputChecker;
        return this;
    }

    private void runTest(boolean doSnapshots) {
        if (doSnapshots) {
            // if we test with snapshots, also do the test without snapshots
            System.out.println("### Running the test with doSnapshots=false");
            runTest(false);
            System.out.println("### Running the test with doSnapshots=true");
        }

        TestInbox inbox = new TestInbox();
        Processor[] processor = {supplier.get()};

        // we'll use 1-capacity outbox to test cooperative emission, if the processor is cooperative
        int outboxCapacity = processor[0].isCooperative() ? 1 : Integer.MAX_VALUE;
        TestOutbox outbox = new TestOutbox(new int[] {outboxCapacity}, outboxCapacity);
        List<Object> actualOutput = new ArrayList<>();

        // create instance of your processor and call the init() method
        processor[0].init(outbox, outbox, new TestProcessorContext());

        // do snapshot+restore before processing any item. This will test saveToSnapshot() in this edge case
        snapshotAndRestore(processor, outbox, actualOutput, doSnapshots);

        // call the process() method
        Iterator<?> inputIterator = input.iterator();
        while (inputIterator.hasNext() || !inbox.isEmpty()) {
            if (inbox.isEmpty()) {
                inbox.add(inputIterator.next());
                if (logInputOutput) {
                    System.out.println("Input: " + inbox.peek());
                }
            }
            checkTime("process", () -> processor[0].process(0, inbox));
            assertTrue("process() call without progress",
                    !assertProgress || inbox.isEmpty() || !outbox.queueWithOrdinal(0).isEmpty());
            if (processor[0].isCooperative() && outbox.queueWithOrdinal(0).size() == 1 && !inbox.isEmpty()) {
                // if the outbox is full, call the process() method again. Cooperative
                // processor must be able to cope with this situation and not try to put
                // more items to the outbox.
                checkTime("process", () -> processor[0].process(0, inbox));
            }
            drainOutbox(outbox.queueWithOrdinal(0), actualOutput, logInputOutput);
            if (inbox.isEmpty()) {
                snapshotAndRestore(processor, outbox, actualOutput, doSnapshots);
            }
        }

        // call the complete() method
        if (callComplete) {
            boolean[] done = {false};
            do {
                checkTime("complete", () -> done[0] = processor[0].complete());
                assertTrue("complete() call without progress",
                        !assertProgress || done[0] || !outbox.queueWithOrdinal(0).isEmpty());
                drainOutbox(outbox.queueWithOrdinal(0), actualOutput, logInputOutput);
                snapshotAndRestore(processor, outbox, actualOutput, doSnapshots);
            } while (!done[0]);
        }

        // assert the outbox
        if (!outputChecker.test(expectedOutput, actualOutput)) {
            assertEquals("processor output doesn't match", listToString(expectedOutput), listToString(actualOutput));
        }
    }

    private void snapshotAndRestore(
            Processor[] processor,
            TestOutbox outbox,
            List<Object> actualOutput,
            boolean enabled) {
        if (!enabled) {
            return;
        }

        // save state of current processor
        TestInbox snapshotInbox = new TestInbox();
        boolean[] done = {false};
        Set<Object> keys = new HashSet<>();
        do {
            checkTime("saveSnapshot", () -> done[0] = processor[0].saveToSnapshot());
            for (Entry<MockData, MockData> entry : outbox.snapshotQueue()) {
                Object key = entry.getKey().getObject();
                assertTrue("Duplicate key produced in saveToSnapshot()\n  Duplicate: " + key + "\n  Keys so far: " + keys,
                        keys.add(key));
                snapshotInbox.add(entry(key, entry.getValue().getObject()));
            }
            assertTrue("saveToSnapshot() call without progress",
                    !assertProgress || done[0] || !outbox.snapshotQueue().isEmpty()
                            || !outbox.queueWithOrdinal(0).isEmpty());
            drainOutbox(outbox.queueWithOrdinal(0), actualOutput, logInputOutput);
            outbox.snapshotQueue().clear();
        } while (!done[0]);

        // restore state to new processor
        processor[0] = supplier.get();
        processor[0].init(outbox, outbox, new TestProcessorContext());

        if (snapshotInbox.isEmpty()) {
            // don't call finishSnapshotRestore, if snapshot was empty
            return;
        }
        int lastInboxSize = snapshotInbox.size();
        while (!snapshotInbox.isEmpty()) {
            checkTime("restoreSnapshot", () -> processor[0].restoreFromSnapshot(snapshotInbox));
            assertTrue("restoreFromSnapshot() call without progress",
                    !assertProgress || lastInboxSize > snapshotInbox.size() || !outbox.queueWithOrdinal(0).isEmpty());
            drainOutbox(outbox.queueWithOrdinal(0), actualOutput, logInputOutput);
            lastInboxSize = snapshotInbox.size();
        }
        do {
            checkTime("finishSnapshotRestore", () -> done[0] = processor[0].finishSnapshotRestore());
            assertTrue("finishSnapshotRestore() call without progress",
                    !assertProgress || done[0] || !outbox.queueWithOrdinal(0).isEmpty());
            drainOutbox(outbox.queueWithOrdinal(0), actualOutput, logInputOutput);
        } while (!done[0]);
    }

    private void checkTime(String methodName, Runnable r) {
        long start = System.nanoTime();
        r.run();
        long elapsed = System.nanoTime() - start;

        if (cooperativeTimeout > 0) {
            assertTrue(String.format("call to %s() took %.1fms, it should be <%dms", methodName,
                    elapsed / (double) MILLISECONDS.toNanos(1), COOPERATIVE_TIME_LIMIT_MS_FAIL),
                    elapsed < MILLISECONDS.toNanos(COOPERATIVE_TIME_LIMIT_MS_FAIL));
        }
        // print warning
        if (elapsed > MILLISECONDS.toNanos(COOPERATIVE_TIME_LIMIT_MS_WARN)) {
            System.out.println(String.format("Warning: call to %s() took %.2fms, it should be <%dms normally",
                    methodName, elapsed / (double) MILLISECONDS.toNanos(1), COOPERATIVE_TIME_LIMIT_MS_WARN));
        }
    }

    /**
     * Move all items from the outbox to the {@code outputList}.
     * @param outboxBucket the queue from Outbox to drain
     * @param outputList target list
     * @param logItems Whether to log drained items to System.out
     */
    public static void drainOutbox(Queue<Object> outboxBucket, List<Object> outputList, boolean logItems) {
        for (Object o; (o = outboxBucket.poll()) != null; ) {
            outputList.add(o);
            if (logItems) {
                System.out.println("Output: " + o);
            }
        }
    }

    /**
     * Gets single processor instance from processor supplier.
     */
    public static Supplier<Processor> supplierFrom(ProcessorSupplier supplier) {
        supplier.init(new TestProcessorSupplierContext());
        return () -> supplier.get(1).iterator().next();
    }

    /**
     * Gets single processor instance from meta processor supplier.
     */
    public static Supplier<Processor> supplierFrom(ProcessorMetaSupplier supplier) {
        supplier.init(new TestProcessorMetaSupplierContext());
        return supplierFrom(supplier.get(singletonList(LOCAL_ADDRESS)).apply(LOCAL_ADDRESS));
    }

    private static String listToString(List<?> list) {
        return list.stream()
                .map(String::valueOf)
                .collect(Collectors.joining("\n"));
    }

    private static Supplier<Processor> singletonSupplier(Processor processor) {
        Processor[] processor1 = {processor};
        return () -> {
            if (processor1[0] == null) {
                throw new RuntimeException("More than one instance requested");
            }
            try {
                return processor1[0];
            } finally {
                processor1[0] = null;
            }
        };
    }
}
