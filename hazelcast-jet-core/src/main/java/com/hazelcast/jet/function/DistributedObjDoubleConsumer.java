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

package com.hazelcast.jet.function;

import java.io.Serializable;
import java.util.function.ObjDoubleConsumer;

/**
 * Represents an operation that accepts an object-valued and a
 * {@code double}-valued argument, and returns no result.  This is the
 * {@code (reference, double)} specialization of {@link DistributedBiConsumer}.
 * Unlike most other functional interfaces, {@code ObjDoubleConsumer} is
 * expected to operate via side-effects.
 *
 * <p>This is a functional interface
 * whose functional method is {@link #accept(Object, double)}.
 *
 * @param <T> the type of the object argument to the operation
 * @see DistributedBiConsumer
 */
@FunctionalInterface
public interface DistributedObjDoubleConsumer<T> extends ObjDoubleConsumer<T>, Serializable {
}
