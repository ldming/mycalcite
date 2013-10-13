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
package net.hydromatic.optiq.impl.java;

import net.hydromatic.linq4j.*;
import net.hydromatic.linq4j.expressions.*;

import net.hydromatic.optiq.*;
import net.hydromatic.optiq.Parameter;
import net.hydromatic.optiq.impl.TableFunctionInSchemaImpl;
import net.hydromatic.optiq.impl.TableInSchemaImpl;

import org.eigenbase.reltype.RelDataType;

import java.lang.reflect.*;
import java.util.*;

/**
 * Implementation of {@link net.hydromatic.optiq.Schema} that exposes the public
 * fields and methods in a Java object.
 */
public class ReflectiveSchema
    extends MapSchema {
  final Class clazz;
  private Object target;

  /**
   * Creates a ReflectiveSchema.
   *
   * @param parentSchema Parent schema
   * @param name Name
   * @param target Object whose fields will be sub-objects of the schema
   * @param expression Expression for schema
   */
  public ReflectiveSchema(
      Schema parentSchema,
      String name,
      Object target,
      Expression expression) {
    super(
        parentSchema,
        parentSchema.getQueryProvider(),
        parentSchema.getTypeFactory(),
        name,
        expression);
    this.clazz = target.getClass();
    this.target = target;
    for (Field field : clazz.getFields()) {
      final String fieldName = field.getName();
      final Table<Object> table = fieldRelation(field);
      if (table == null) {
        continue;
      }
      tableMap.put(
          fieldName,
          new TableInSchemaImpl(
              this, fieldName, TableType.TABLE, table));
    }
    for (Method method : clazz.getMethods()) {
      final String methodName = method.getName();
      if (method.getDeclaringClass() == Object.class
          || methodName.equals("toString")) {
        continue;
      }
      final TableFunction tableFunction = methodMember(method);
      membersMap.put(methodName,
          new TableFunctionInSchemaImpl(this, methodName, tableFunction));
    }
  }

  /**
   * Creates a ReflectiveSchema within another schema.
   *
   * @param parentSchema Parent schema
   * @param name Name of new schema
   * @param target Object whose fields become the tables of the schema
   * @return New ReflectiveSchema
   */
  public static ReflectiveSchema create(
      MutableSchema parentSchema,
      String name,
      Object target) {
    ReflectiveSchema schema =
        new ReflectiveSchema(
            parentSchema,
            name,
            target,
            parentSchema.getSubSchemaExpression(
                name, ReflectiveSchema.class));
    parentSchema.addSchema(name, schema);
    return schema;
  }

  @Override
  public String toString() {
    return "ReflectiveSchema(target=" + target + ")";
  }

  /** Returns the wrapped object. (May not appear to be used, but is used in
   * generated code via {@link BuiltinMethod#REFLECTIVE_SCHEMA_GET_TARGET}.) */
  public Object getTarget() {
    return target;
  }

  public <T> TableFunction<T> methodMember(final Method method) {
    final ReflectiveSchema schema = this;
    final Type elementType = getElementType(method.getReturnType());
    final RelDataType relDataType = typeFactory.createType(elementType);
    final Class<?>[] parameterTypes = method.getParameterTypes();
    return new TableFunction<T>() {
      public String toString() {
        return "Member {method=" + method + "}";
      }

      public Type getElementType() {
        return elementType;
      }

      public List<Parameter> getParameters() {
        return new AbstractList<Parameter>() {
          public Parameter get(final int index) {
            return new Parameter() {
              public int getOrdinal() {
                return index;
              }

              public String getName() {
                return "arg" + index;
              }

              public RelDataType getType() {
                return typeFactory.createJavaType(
                    parameterTypes[index]);
              }
            };
          }

          public int size() {
            return parameterTypes.length;
          }
        };
      }

      public Table<T> apply(final List<Object> arguments) {
        final List<Expression> list = new ArrayList<Expression>();
        for (Object argument : arguments) {
          list.add(Expressions.constant(argument));
        }
        try {
          final Object o = method.invoke(schema, arguments.toArray());
          return new ReflectiveTable<T>(
              schema,
              elementType,
              relDataType,
              Expressions.call(
                  schema.getTargetExpression(),
                  method,
                  list)) {
            public Enumerator<T> enumerator() {
              @SuppressWarnings("unchecked")
              final Enumerable<T> enumerable = toEnumerable(o);
              return enumerable.enumerator();
            }
          };
        } catch (IllegalAccessException e) {
          throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
          throw new RuntimeException(e);
        }
      }
    };
  }

  /** Returns an expression for the object wrapped by this schema (not the
   * schema itself). */
  Expression getTargetExpression() {
    return Types.castIfNecessary(
        target.getClass(),
        Expressions.call(
            Types.castIfNecessary(
                ReflectiveSchema.class,
                getExpression()),
            BuiltinMethod.REFLECTIVE_SCHEMA_GET_TARGET.method));
  }

  /** Returns a table based on a particular field of this schema. If the
   * field is not of the right type to be a relation, returns null. */
  private <T> Table<T> fieldRelation(final Field field) {
    final Type elementType = getElementType(field.getType());
    if (elementType == null) {
      return null;
    }
    final RelDataType relDataType = typeFactory.createType(elementType);
    return new ReflectiveTable<T>(
        this,
        elementType,
        relDataType,
        Expressions.field(
            ReflectiveSchema.this.getTargetExpression(),
            field)) {
      public String toString() {
        return "Relation {field=" + field.getName() + "}";
      }

      public Enumerator<T> enumerator() {
        try {
          Object o = field.get(target);
          @SuppressWarnings("unchecked")
          Enumerable<T> enumerable1 = toEnumerable(o);
          return enumerable1.enumerator();
        } catch (IllegalAccessException e) {
          throw new RuntimeException(
              "Error while accessing field " + field, e);
        }
      }
    };
  }

  /** Deduces the element type of a collection;
   * same logic as {@link #toEnumerable} */
  private static Type getElementType(Class clazz) {
    if (clazz.isArray()) {
      return clazz.getComponentType();
    }
    if (Iterable.class.isAssignableFrom(clazz)) {
      return Object.class;
    }
    return null; // not a collection/array/iterable
  }

  private static Enumerable toEnumerable(final Object o) {
    if (o.getClass().isArray()) {
      if (o instanceof Object[]) {
        return Linq4j.asEnumerable((Object[]) o);
      } else {
        return Linq4j.asEnumerable(Primitive.asList(o));
      }
    }
    if (o instanceof Iterable) {
      return Linq4j.asEnumerable((Iterable) o);
    }
    throw new RuntimeException(
        "Cannot convert " + o.getClass() + " into a Enumerable");
  }

  private static abstract class ReflectiveTable<T>
      extends BaseQueryable<T>
      implements Table<T> {
    private final ReflectiveSchema schema;
    private final RelDataType relDataType;

    public ReflectiveTable(
        ReflectiveSchema schema,
        Type elementType,
        RelDataType relDataType,
        Expression expression) {
      super(schema.getQueryProvider(), elementType, expression);
      this.schema = schema;
      this.relDataType = relDataType;
    }

    public RelDataType getRowType() {
      return relDataType;
    }

    public Statistic getStatistic() {
      return Statistics.UNKNOWN;
    }
  }

  /** Factory that creates a schema by instantiating an object and looking at
   * its public fields.
   *
   * <p>The following example instantiates a {@code FoodMart} object as a schema
   * that contains tables called {@code EMPS} and {@code DEPTS} based on the
   * object's fields.</p>
   *
   * <pre>
   * {@code schemas: [
   *     {
   *       name: "foodmart",
   *       type: "custom",
   *       factory: "net.hydromatic.optiq.impl.java.ReflectiveSchema.Factory",
   *       operand: {
   *         class: "com.acme.FoodMart",
   *         staticMethod: "instance"
   *       }
   *     }
   *   ]
   *
   * class FoodMart {
   *   public static final FoodMart instance() {
   *     return new FoodMart();
   *   }
   *
   *   Employee[] EMPS;
   *   Department[] DEPTS;
   * }
   * }</pre>
   * */
  public static class Factory implements SchemaFactory {
    public Schema create(MutableSchema schema, String name,
        Map<String, Object> operand) {
      Class clazz;
      Object target;
      final Object className = operand.get("class");
      if (className != null) {
        try {
          clazz = Class.forName((String) className);
        } catch (ClassNotFoundException e) {
          throw new RuntimeException("Error loading class " + className, e);
        }
      } else {
        throw new RuntimeException("Operand 'class' is required");
      }
      final Object methodName = operand.get("staticMethod");
      if (methodName != null) {
        try {
          //noinspection unchecked
          Method method = clazz.getMethod((String) methodName);
          target = method.invoke(null);
        } catch (Exception e) {
          throw new RuntimeException("Error invoking method " + methodName, e);
        }
      } else {
        try {
          target = clazz.newInstance();
        } catch (Exception e) {
          throw new RuntimeException("Error instantiating class " + className,
              e);
        }
      }
      return ReflectiveSchema.create(schema, name, target);
    }
  }
}

// End ReflectiveSchema.java
