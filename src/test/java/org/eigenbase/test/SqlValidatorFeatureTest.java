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
package org.eigenbase.test;

import org.eigenbase.reltype.*;
import org.eigenbase.resgen.*;
import org.eigenbase.resource.*;
import org.eigenbase.sql.*;
import org.eigenbase.sql.parser.*;
import org.eigenbase.sql.test.*;
import org.eigenbase.sql.type.*;
import org.eigenbase.sql.validate.*;
import org.eigenbase.util.*;

import org.junit.Test;

/**
 * SqlValidatorFeatureTest verifies that features can be independently enabled
 * or disabled.
 */
public class SqlValidatorFeatureTest extends SqlValidatorTestCase {
  //~ Static fields/initializers ---------------------------------------------

  private static final String FEATURE_DISABLED = "feature_disabled";

  //~ Instance fields --------------------------------------------------------

  private ResourceDefinition disabledFeature;

  //~ Constructors -----------------------------------------------------------

  public SqlValidatorFeatureTest() {
    super();
  }

  //~ Methods ----------------------------------------------------------------

  @Override
  public SqlTester getTester() {
    return new SqlTesterImpl(new FeatureTesterFactory());
  }

  @Test public void testDistinct() {
    checkFeature(
        "select ^distinct^ name from dept",
        EigenbaseResource.instance().SQLFeature_E051_01);
  }

  @Test public void testOrderByDesc() {
    checkFeature(
        "select name from dept order by ^name desc^",
        EigenbaseResource.instance().SQLConformance_OrderByDesc);
  }

  // NOTE jvs 6-Mar-2006:  carets don't come out properly placed
  // for INTERSECT/EXCEPT, so don't bother

  @Test public void testIntersect() {
    checkFeature(
        "^select name from dept intersect select name from dept^",
        EigenbaseResource.instance().SQLFeature_F302);
  }

  @Test public void testExcept() {
    checkFeature(
        "^select name from dept except select name from dept^",
        EigenbaseResource.instance().SQLFeature_E071_03);
  }

  @Test public void testMultiset() {
    checkFeature(
        "values ^multiset[1]^",
        EigenbaseResource.instance().SQLFeature_S271);

    checkFeature(
        "values ^multiset(select * from dept)^",
        EigenbaseResource.instance().SQLFeature_S271);
  }

  @Test public void testTablesample() {
    checkFeature(
        "select name from ^dept tablesample bernoulli(50)^",
        EigenbaseResource.instance().SQLFeature_T613);

    checkFeature(
        "select name from ^dept tablesample substitute('sample_dept')^",
        EigenbaseResource.instance().SQLFeatureExt_T613_Substitution);
  }

  private void checkFeature(String sql, ResourceDefinition feature) {
    // Test once with feature enabled:  should pass
    check(sql);

    // Test once with feature disabled:  should fail
    try {
      disabledFeature = feature;
      checkFails(sql, FEATURE_DISABLED);
    } finally {
      disabledFeature = null;
    }
  }

  //~ Inner Classes ----------------------------------------------------------

  private class FeatureTesterFactory extends DelegatingSqlTestFactory {
    public FeatureTesterFactory() {
      super(DefaultSqlTestFactory.INSTANCE);
    }

    @Override
    public SqlValidator getValidator(SqlTestFactory factory) {
      final RelDataTypeFactory typeFactory = new SqlTypeFactoryImpl();
      SqlConformance conformance = (SqlConformance) get("conformance");
      final boolean caseSensitive = (Boolean) get("caseSensitive");
      return new FeatureValidator(
          factory.createOperatorTable(),
          new MockCatalogReader(typeFactory, caseSensitive),
          typeFactory,
          conformance);
    }
  }

  private class FeatureValidator extends SqlValidatorImpl {
    protected FeatureValidator(
        SqlOperatorTable opTab,
        SqlValidatorCatalogReader catalogReader,
        RelDataTypeFactory typeFactory,
        SqlConformance conformance) {
      super(opTab, catalogReader, typeFactory, conformance);
    }

    protected void validateFeature(
        ResourceDefinition feature,
        SqlParserPos context) {
      if (feature == disabledFeature) {
        EigenbaseException ex =
            new EigenbaseException(
                FEATURE_DISABLED,
                null);
        if (context == null) {
          throw ex;
        }
        throw new EigenbaseContextException(
            "location",
            ex,
            context.getLineNum(),
            context.getColumnNum(),
            context.getEndLineNum(),
            context.getEndColumnNum());
      }
    }
  }
}

// End SqlValidatorFeatureTest.java
