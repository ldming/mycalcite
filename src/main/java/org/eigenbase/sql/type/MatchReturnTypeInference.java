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
package org.eigenbase.sql.type;

import java.util.Arrays;
import java.util.List;

import org.eigenbase.reltype.*;
import org.eigenbase.sql.*;
import org.eigenbase.util.*;

/**
 * Returns the first type that matches a set of given {@link SqlTypeName}s. If
 * no match could be found, null is returned.
 */
public class MatchReturnTypeInference
    implements SqlReturnTypeInference
{
    //~ Instance fields --------------------------------------------------------

    private final int start;
    private final List<SqlTypeName> typeNames;

    //~ Constructors -----------------------------------------------------------

    /**
     * Returns the first type of typeName at or after position start (zero
     * based).
     *
     * @see #MatchReturnTypeInference(int, SqlTypeName[])
     */
    public MatchReturnTypeInference(int start, SqlTypeName... typeName)
    {
        this(start, Arrays.asList(typeName));
    }

    /**
     * Returns the first type matching any type in typeNames at or after
     * position start (zero based).
     */
    public MatchReturnTypeInference(int start, List<SqlTypeName> typeNames)
    {
        assert start >= 0;
        assert null != typeNames;
        assert typeNames.size() > 0;
        this.start = start;
        this.typeNames = typeNames;
    }

    //~ Methods ----------------------------------------------------------------

    public RelDataType inferReturnType(
        SqlOperatorBinding opBinding)
    {
        for (int i = start; i < opBinding.getOperandCount(); i++) {
            RelDataType argType = opBinding.getOperandType(i);
            if (SqlTypeUtil.isOfSameTypeName(typeNames, argType)) {
                return argType;
            }
        }
        return null;
    }
}

// End MatchReturnTypeInference.java
