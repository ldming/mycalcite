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
package org.eigenbase.sql.fun;

import java.util.AbstractList;
import java.util.Map;

import org.eigenbase.reltype.*;
import org.eigenbase.sql.*;
import org.eigenbase.sql.type.*;
import org.eigenbase.util.Pair;

/**
 * SqlRowOperator represents the special ROW constructor.
 *
 * <p>TODO: describe usage for row-value construction and row-type construction
 * (SQL supports both).
 */
public class SqlRowOperator extends SqlSpecialOperator {
  //~ Constructors -----------------------------------------------------------

  public SqlRowOperator() {
    super(
        "ROW",
        SqlKind.ROW, MDX_PRECEDENCE,
        false,
        null,
        InferTypes.RETURN_TYPE,
        OperandTypes.VARIADIC);
  }

  //~ Methods ----------------------------------------------------------------

  // implement SqlOperator
  public SqlSyntax getSyntax() {
    // Function syntax would work too.
    return SqlSyntax.SPECIAL;
  }

  public RelDataType inferReturnType(
      final SqlOperatorBinding opBinding) {
    // The type of a ROW(e1,e2) expression is a record with the types
    // {e1type,e2type}.  According to the standard, field names are
    // implementation-defined.
    return opBinding.getTypeFactory().createStructType(
        new AbstractList<Map.Entry<String, RelDataType>>() {
          public Map.Entry<String, RelDataType> get(int index) {
            return Pair.of(
                SqlUtil.deriveAliasFromOrdinal(index),
                opBinding.getOperandType(index));
          }

          public int size() {
            return opBinding.getOperandCount();
          }
        });
  }

  public void unparse(
      SqlWriter writer,
      SqlCall call,
      int leftPrec,
      int rightPrec) {
    SqlUtil.unparseFunctionSyntax(this, writer, call, true, null);
  }

  // override SqlOperator
  public boolean requiresDecimalExpansion() {
    return false;
  }
}

// End SqlRowOperator.java
