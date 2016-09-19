/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.jet.impl.runtime.task.processors.factory;


import com.hazelcast.jet.Processor;
import com.hazelcast.jet.impl.runtime.task.TaskProcessor;
import com.hazelcast.jet.impl.runtime.task.processors.shuffling.ShuffledActorTaskProcessor;
import com.hazelcast.jet.impl.runtime.task.processors.shuffling.ShuffledConsumerTaskProcessor;
import com.hazelcast.jet.impl.runtime.task.processors.shuffling.ShuffledReceiverConsumerTaskProcessor;
import com.hazelcast.jet.runtime.Consumer;
import com.hazelcast.jet.runtime.Producer;
import com.hazelcast.jet.runtime.TaskContext;

public class ShuffledTaskProcessorFactory extends DefaultTaskProcessorFactory {
    @Override
    public TaskProcessor consumerTaskProcessor(Consumer[] consumers,
                                               Processor processor,
                                               TaskContext taskContext) {
        return actorTaskProcessor(
                new Producer[0],
                consumers,
                processor,
                taskContext
        );
    }

    @Override
    public TaskProcessor actorTaskProcessor(Producer[] producers,
                                            Consumer[] consumers,
                                            Processor processor,
                                            TaskContext taskContext) {
        return new ShuffledActorTaskProcessor(
                producers,
                processor,
                taskContext,
                new ShuffledConsumerTaskProcessor(consumers, processor, taskContext),
                new ShuffledReceiverConsumerTaskProcessor(consumers, processor, taskContext)
        );
    }
}