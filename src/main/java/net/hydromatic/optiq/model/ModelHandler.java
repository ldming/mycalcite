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
package net.hydromatic.optiq.model;

import net.hydromatic.optiq.*;
import net.hydromatic.optiq.impl.*;
import net.hydromatic.optiq.impl.java.MapSchema;
import net.hydromatic.optiq.impl.jdbc.JdbcSchema;
import net.hydromatic.optiq.jdbc.OptiqConnection;

import org.apache.commons.dbcp.BasicDataSource;

import org.eigenbase.util.Pair;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;

import static org.eigenbase.util.Stacks.*;

/**
 * Reads a model and creates schema objects accordingly.
 */
public class ModelHandler {
  private final OptiqConnection connection;
  private final List<Pair<String, Schema>> schemaStack =
      new ArrayList<Pair<String, Schema>>();

  public ModelHandler(OptiqConnection connection, String uri)
      throws IOException {
    super();
    this.connection = connection;
    final ObjectMapper mapper = new ObjectMapper();
    mapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
    mapper.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
    JsonRoot root;
    if (uri.startsWith("inline:")) {
      root = mapper.readValue(
          uri.substring("inline:".length()), JsonRoot.class);
    } else {
      root = mapper.readValue(new File(uri), JsonRoot.class);
    }
    visit(root);
  }

  public void visit(JsonRoot root) {
    final Pair<String, Schema> pair =
        Pair.<String, Schema>of(null, connection.getRootSchema());
    push(schemaStack, pair);
    for (JsonSchema schema : root.schemas) {
      schema.accept(this);
    }
    pop(schemaStack, pair);
    if (root.defaultSchema != null) {
      try {
        connection.setSchema(root.defaultSchema);
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public void visit(JsonMapSchema jsonSchema) {
    final MutableSchema parentSchema = currentMutableSchema("schema");
    final MapSchema schema = MapSchema.create(parentSchema, jsonSchema.name);
    schema.initialize();
    populateSchema(jsonSchema, schema);
  }

  private void populateSchema(JsonSchema jsonSchema, Schema schema) {
    final Pair<String, Schema> pair = Pair.of(jsonSchema.name, schema);
    push(schemaStack, pair);
    jsonSchema.visitChildren(this);
    pop(schemaStack, pair);
  }

  public void visit(JsonCustomSchema jsonSchema) {
    try {
      final MutableSchema parentSchema = currentMutableSchema("sub-schema");
      final Class clazz = Class.forName(jsonSchema.factory);
      final SchemaFactory schemaFactory = (SchemaFactory) clazz.newInstance();
      final Schema schema = schemaFactory.create(
          parentSchema, jsonSchema.name, jsonSchema.operand);
      parentSchema.addSchema(jsonSchema.name, schema);
      if (schema instanceof MapSchema) {
        ((MapSchema) schema).initialize();
      }
      populateSchema(jsonSchema, schema);
    } catch (Exception e) {
      throw new RuntimeException("Error instantiating " + jsonSchema, e);
    }
  }

  public void visit(JsonJdbcSchema jsonSchema) {
    JdbcSchema schema =
        JdbcSchema.create(
            currentMutableSchema("jdbc schema"),
            dataSource(jsonSchema),
            jsonSchema.jdbcCatalog,
            jsonSchema.jdbcSchema,
            jsonSchema.name);
    populateSchema(jsonSchema, schema);
  }

  public void visit(JsonMaterialization jsonMaterialization) {
    try {
      final Schema schema = currentSchema();
      if (!(schema instanceof MutableSchema)) {
        throw new RuntimeException(
            "Cannot define materialization; parent schema '"
            + currentSchemaName()
            + "' is not a SemiMutableSchema");
      }
      final MutableSchema mutableSchema = (MutableSchema) schema;
      Schema.TableFunctionInSchema tableFunctionInSchema =
          MaterializedViewTable.create(
              schema,
              jsonMaterialization.view,
              jsonMaterialization.sql,
              null,
              jsonMaterialization.table);
      mutableSchema.addTableFunction(tableFunctionInSchema);
    } catch (Exception e) {
      throw new RuntimeException("Error instantiating " + jsonMaterialization,
          e);
    }
  }

  private DataSource dataSource(JsonJdbcSchema jsonJdbcSchema) {
    BasicDataSource dataSource = new BasicDataSource();
    dataSource.setUrl(jsonJdbcSchema.jdbcUrl);
    dataSource.setUsername(jsonJdbcSchema.jdbcUser);
    dataSource.setPassword(jsonJdbcSchema.jdbcPassword);
    return dataSource;
  }

  public void visit(JsonCustomTable jsonTable) {
    try {
      final MutableSchema schema = currentMutableSchema("table");
      final Class clazz = Class.forName(jsonTable.factory);
      final TableFactory tableFactory = (TableFactory) clazz.newInstance();
      final Table table = tableFactory.create(
          schema, jsonTable.name, jsonTable.operand, null);
      schema.addTable(
          new TableInSchemaImpl(
              schema, jsonTable.name, Schema.TableType.TABLE, table));
    } catch (Exception e) {
      throw new RuntimeException("Error instantiating " + jsonTable, e);
    }
  }

  public void visit(JsonView jsonView) {
    try {
      final MutableSchema schema = currentMutableSchema("view");
      final List<String> path =
          jsonView.path == null
              ? currentSchemaPath()
              : jsonView.path;
      schema.addTableFunction(
          ViewTable.viewFunction(
              schema, jsonView.name, jsonView.sql, path));
    } catch (Exception e) {
      throw new RuntimeException("Error instantiating " + jsonView, e);
    }
  }

  private List<String> currentSchemaPath() {
    return Collections.singletonList(peek(schemaStack).left);
  }

  private Schema currentSchema() {
    return peek(schemaStack).right;
  }

  private String currentSchemaName() {
    return peek(schemaStack).left;
  }

  private MutableSchema currentMutableSchema(String elementType) {
    final Schema schema = currentSchema();
    if (schema instanceof MutableSchema) {
      return (MutableSchema) schema;
    }
    throw new RuntimeException(
        "Cannot define " + elementType + "; parent schema " + schema
        + " is not mutable");
  }
}

// End ModelHandler.java
