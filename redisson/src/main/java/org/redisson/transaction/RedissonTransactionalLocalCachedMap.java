/**
 * Copyright 2018 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.transaction;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.redisson.api.RFuture;
import org.redisson.api.RLocalCachedMap;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.transaction.operation.TransactionalOperation;

/**
 * 
 * @author Nikita Koksharov
 *
 * @param <K> key type
 * @param <V> value type
 */
public class RedissonTransactionalLocalCachedMap<K, V> extends RedissonTransactionalMap<K, V> implements RLocalCachedMap<K, V> {

    public RedissonTransactionalLocalCachedMap(CommandAsyncExecutor commandExecutor,  
            List<TransactionalOperation> operations, long timeout, AtomicBoolean executed, RLocalCachedMap<K, V> innerMap) {
        super(commandExecutor, operations, timeout, executed, innerMap);
    }

    @Override
    public void destroy() {
        throw new UnsupportedOperationException("destroy method is not supported in transaction");
    }

    @Override
    public void preloadCache() {
        throw new UnsupportedOperationException("preloadCache method is not supported in transaction");
    }

    @Override
    public RFuture<Void> clearLocalCacheAsync() {
        throw new UnsupportedOperationException("clearLocalCache method is not supported in transaction");
    }

    @Override
    public void clearLocalCache() {
        throw new UnsupportedOperationException("clearLocalCache method is not supported in transaction");
    }

}
