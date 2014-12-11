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
package org.apache.calcite.sql.fun;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperandCountRange;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandCountRanges;

/**
 * SqlQuarterFunction represents the SQL:1999 standard {@code QUARTER}
 * function. Determines Quarter (1,2,3,4) of a given date.
 */
public class SqlQuarterFunction extends SqlFunction {
  //~ Constructors -----------------------------------------------------------

  public SqlQuarterFunction() {
    super("QUARTER",
        SqlKind.OTHER,
        ReturnTypes.BIGINT_NULLABLE,
        InferTypes.FIRST_KNOWN,
        OperandTypes.DATETIME,
        SqlFunctionCategory.TIMEDATE);
  }

  //~ Methods ----------------------------------------------------------------

  public SqlOperandCountRange getOperandCountRange() {
    return SqlOperandCountRanges.of(1);
  }

  public String getSignatureTemplate(int operandsCount) {
    assert 1 == operandsCount;
    return "{0}({1})";
  }

  public boolean checkOperandTypes(SqlCallBinding callBinding,
      boolean throwOnFailure) {
    SqlCall call = callBinding.getCall();
    return OperandTypes.DATETIME.checkSingleOperandType(callBinding,
        call.operand(0), 0, throwOnFailure);
  }
}

// End SqlQuarterFunction.java
