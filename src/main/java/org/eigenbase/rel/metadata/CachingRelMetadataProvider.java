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
package org.eigenbase.rel.metadata;

import java.lang.reflect.*;
import java.util.*;

import org.eigenbase.rel.*;
import org.eigenbase.relopt.*;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;

/**
 * Implementation of the {@link RelMetadataProvider}
 * interface that caches results from an underlying provider.
 */
public class CachingRelMetadataProvider implements RelMetadataProvider {
  //~ Instance fields --------------------------------------------------------

  private final Map<List, CacheEntry> cache;

  private final RelMetadataProvider underlyingProvider;

  private final RelOptPlanner planner;

  private static final Object NULL_SENTINEL = new Object() {
    @Override
    public String toString() {
      return "{null}";
    }
  };

  //~ Constructors -----------------------------------------------------------

  public CachingRelMetadataProvider(
      RelMetadataProvider underlyingProvider,
      RelOptPlanner planner) {
    this.underlyingProvider = underlyingProvider;
    this.planner = planner;

    cache = new HashMap<List, CacheEntry>();
  }

  //~ Methods ----------------------------------------------------------------

  public Function<RelNode, Metadata> apply(Class<? extends RelNode> relClass,
      final Class<? extends Metadata> metadataClass) {
    final Function<RelNode, Metadata> function =
        underlyingProvider.apply(relClass, metadataClass);
    if (function == null) {
      return null;
    }

    // TODO jvs 30-Mar-2006: Use meta-metadata to decide which metadata
    // query results can stay fresh until the next Ice Age.
    return new Function<RelNode, Metadata>() {
      public Metadata apply(RelNode input) {
        final Metadata metadata = function.apply(input);
        return (Metadata) Proxy.newProxyInstance(metadataClass.getClassLoader(),
            new Class[]{metadataClass}, new CachingInvocationHandler(metadata));
      }
    };
  }

  //~ Inner Classes ----------------------------------------------------------

  private static class CacheEntry {
    long timestamp;

    Object result;
  }

  private class CachingInvocationHandler implements InvocationHandler {
    private final Metadata metadata;

    public CachingInvocationHandler(Metadata metadata) {
      this.metadata = metadata;
    }

    public Object invoke(Object proxy, Method method, Object[] args)
      throws Throwable {
      // Compute hash key.
      final ImmutableList.Builder<Object> builder = ImmutableList.builder();
      builder.add(method);
      builder.add(metadata.rel());
      if (args != null) {
        for (Object arg : args) {
          // Replace null values because ImmutableList does not allow them.
          builder.add(arg == null ? NULL_SENTINEL : arg);
        }
      }
      List<Object> key = builder.build();

      long timestamp = planner.getRelMetadataTimestamp(metadata.rel());

      // Perform cache lookup.
      CacheEntry entry = cache.get(key);
      if (entry != null) {
        if (timestamp == entry.timestamp) {
          return entry.result;
        }
      }

      // Cache miss or stale.
      Object result = method.invoke(metadata, args);
      if (result != null) {
        entry = new CacheEntry();
        entry.timestamp = timestamp;
        entry.result = result;
        cache.put(key, entry);
      }
      return result;
    }
  }
}

// End CachingRelMetadataProvider.java
