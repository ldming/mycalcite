/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.jdbc;

import org.apache.calcite.schema.Function;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.TableMacro;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

/**
 * A concrete implementation of {@link org.apache.calcite.jdbc.CalciteSchema}
 * that maintains minimal state.
 */
class SimpleCalciteSchema extends CalciteSchema {
  /** Creates a CachingCalciteSchema.
   *
   * <p>Use {@link CalciteSchema#createRootSchema(boolean)}
   * or {@link #add(String, Schema)}. */
  SimpleCalciteSchema(CalciteSchema parent, Schema schema, String name) {
    super(parent, schema, name);
  }

  public void setCache(boolean cache) {
    throw new UnsupportedOperationException();
  }

  public CalciteSchema add(String name, Schema schema) {
    final CalciteSchema calciteSchema =
        new SimpleCalciteSchema(this, schema, name);
    subSchemaMap.put(name, calciteSchema);
    return calciteSchema;
  }

  protected CalciteSchema getImplicitSubSchema(String schemaName,
      boolean caseSensitive) {
    // Check implicit schemas.
    Schema s = schema.getSubSchema(schemaName);
    if (s != null) {
      return new SimpleCalciteSchema(this, s, schemaName);
    }
    return null;
  }

  protected TableEntry getImplicitTable(String tableName,
      boolean caseSensitive) {
    // Check implicit tables.
    Table table = schema.getTable(tableName);
    if (table != null) {
      return tableEntry(tableName, table);
    }
    return null;
  }

  protected void addImplicitSubSchemaToBuilder(
      ImmutableSortedMap.Builder<String, CalciteSchema> builder) {
    ImmutableSortedMap<String, CalciteSchema> explicitSubSchemas = builder.build();
    for (String schemaName : schema.getSubSchemaNames()) {
      if (explicitSubSchemas.containsKey(schemaName)) {
        // explicit subschema wins.
        continue;
      }
      Schema s = schema.getSubSchema(schemaName);
      if (s != null) {
        CalciteSchema calciteSchema = new SimpleCalciteSchema(this, s, schemaName);
        builder.put(schemaName, calciteSchema);
      }
    }
  }

  protected void addImplicitTableToBuilder(ImmutableSortedSet.Builder<String> builder) {
    builder.addAll(schema.getTableNames());
  }

  protected void addImplicitFunctionToBuilder(ImmutableList.Builder<Function> builder) {
    for (String functionName : schema.getFunctionNames()) {
      builder.addAll(schema.getFunctions(functionName));
    }
  }

  protected void addImplicitFuncNamesToBuilder(ImmutableSortedSet.Builder<String> builder) {
    builder.addAll(schema.getFunctionNames());
  }

  protected void addImplicitTablesBasedOnNullaryFunctionsToBuilder(
      ImmutableSortedMap.Builder<String, Table> builder) {
    ImmutableSortedMap<String, Table> explicitTables = builder.build();

    for (String s : schema.getFunctionNames()) {
      // explicit table wins.
      if (explicitTables.containsKey(s)) {
        continue;
      }
      for (Function function : schema.getFunctions(s)) {
        if (function instanceof TableMacro
            && function.getParameters().isEmpty()) {
          final Table table = ((TableMacro) function).apply(ImmutableList.of());
          builder.put(s, table);
        }
      }
    }
  }

  protected TableEntry getImplicitTableBasedOnNullaryFunction(String tableName,
      boolean caseSensitive) {
    for (String s : schema.getFunctionNames()) {
      for (Function function : schema.getFunctions(s)) {
        if (function instanceof TableMacro
            && function.getParameters().isEmpty()) {
          final Table table = ((TableMacro) function).apply(ImmutableList.of());
          return tableEntry(tableName, table);
        }
      }
    }
    return null;
  }

  protected boolean isCacheEnabled() {
    return false;
  }

}

// End SimpleCalciteSchema.java
