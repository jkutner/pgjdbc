/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.PgStatement;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/*
 * Test for getObject
 */
public class StatementTest {
  private Connection con;

  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();
    TestUtil.createTempTable(con, "test_statement", "i int");
    TestUtil.createTempTable(con, "escapetest",
        "ts timestamp, d date, t time, \")\" varchar(5), \"\"\"){a}'\" text ");
    TestUtil.createTempTable(con, "comparisontest", "str1 varchar(5), str2 varchar(15)");
    Statement stmt = con.createStatement();
    stmt.executeUpdate(TestUtil.insertSQL("comparisontest", "str1,str2", "'_abcd','_found'"));
    stmt.executeUpdate(TestUtil.insertSQL("comparisontest", "str1,str2", "'%abcd','%found'"));
    stmt.close();
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.dropTable(con, "test_statement");
    TestUtil.dropTable(con, "escapetest");
    TestUtil.dropTable(con, "comparisontest");
    con.close();
  }

  @Test
  public void testClose() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.close();

    try {
      stmt.getResultSet();
      fail("statements should not be re-used after close");
    } catch (SQLException ex) {
    }
  }

  /**
   * Closing a Statement twice is not an error.
   */
  @Test
  public void testDoubleClose() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.close();
    stmt.close();
  }

  @Test
  public void testMultiExecute() throws SQLException {
    Statement stmt = con.createStatement();
    assertTrue(stmt.execute("SELECT 1 as a; UPDATE test_statement SET i=1; SELECT 2 as b, 3 as c"));

    ResultSet rs = stmt.getResultSet();
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    rs.close();

    assertTrue(!stmt.getMoreResults());
    assertEquals(0, stmt.getUpdateCount());

    assertTrue(stmt.getMoreResults());
    rs = stmt.getResultSet();
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    rs.close();

    assertTrue(!stmt.getMoreResults());
    assertEquals(-1, stmt.getUpdateCount());
    stmt.close();
  }

  @Test
  public void testEmptyQuery() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute("");
    assertNull(stmt.getResultSet());
    assertTrue(!stmt.getMoreResults());
  }

  @Test
  public void testUpdateCount() throws SQLException {
    Statement stmt = con.createStatement();
    int count;

    count = stmt.executeUpdate("INSERT INTO test_statement VALUES (3)");
    assertEquals(1, count);
    count = stmt.executeUpdate("INSERT INTO test_statement VALUES (3)");
    assertEquals(1, count);

    count = stmt.executeUpdate("UPDATE test_statement SET i=4");
    assertEquals(2, count);

    count = stmt.executeUpdate("CREATE TEMP TABLE another_table (a int)");
    assertEquals(0, count);

    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_0)) {
      count = stmt.executeUpdate("CREATE TEMP TABLE yet_another_table AS SELECT x FROM generate_series(1,10) x");
      assertEquals(10, count);
    }
  }

  @Test
  public void testEscapeProcessing() throws SQLException {
    Statement stmt = con.createStatement();
    int count;

    count = stmt.executeUpdate("insert into escapetest (ts) values ({ts '1900-01-01 00:00:00'})");
    assertEquals(1, count);

    count = stmt.executeUpdate("insert into escapetest (d) values ({d '1900-01-01'})");
    assertEquals(1, count);

    count = stmt.executeUpdate("insert into escapetest (t) values ({t '00:00:00'})");
    assertEquals(1, count);

    ResultSet rs = stmt.executeQuery("select {fn version()} as version");
    assertTrue(rs.next());

    // check nested and multiple escaped functions
    rs = stmt.executeQuery("select {fn version()} as version, {fn log({fn log(3.0)})} as log");
    assertTrue(rs.next());
    assertEquals(Math.log(Math.log(3)), rs.getDouble(2), 0.00001);

    stmt.executeUpdate("UPDATE escapetest SET \")\" = 'a', \"\"\"){a}'\" = 'b'");

    // check "difficult" values
    rs = stmt.executeQuery("select {fn concat(')',escapetest.\")\")} as concat"
        + ", {fn concat('{','}')} "
        + ", {fn concat('''','\"')} "
        + ", {fn concat(\"\"\"){a}'\", '''}''')} "
        + " FROM escapetest");
    assertTrue(rs.next());
    assertEquals(")a", rs.getString(1));
    assertEquals("{}", rs.getString(2));
    assertEquals("'\"", rs.getString(3));
    assertEquals("b'}'", rs.getString(4));

    count = stmt.executeUpdate("create temp table b (i int)");
    assertEquals(0, count);

    rs = stmt.executeQuery("select * from {oj test_statement a left outer join b on (a.i=b.i)} ");
    assertTrue(!rs.next());
    // test escape escape character
    rs = stmt
        .executeQuery("select str2 from comparisontest where str1 like '|_abcd' {escape '|'} ");
    assertTrue(rs.next());
    assertEquals("_found", rs.getString(1));
    rs = stmt
        .executeQuery("select str2 from comparisontest where str1 like '|%abcd' {escape '|'} ");
    assertTrue(rs.next());
    assertEquals("%found", rs.getString(1));
  }


  @Test
  public void testPreparedFunction() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("SELECT {fn concat('a', ?)}");
    pstmt.setInt(1, 5);
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertEquals("a5", rs.getString(1));
  }

  @Test
  public void testDollarInComment() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("SELECT /* $ */ {fn curdate()}");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertNotNull("{fn curdate()} should be not null", rs.getString(1));
  }

  @Test
  public void testDollarInCommentTwoComments() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("SELECT /* $ *//* $ */ {fn curdate()}");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    assertNotNull("{fn curdate()} should be not null", rs.getString(1));
  }

  @Test
  public void testNumericFunctions() throws SQLException {
    Statement stmt = con.createStatement();

    ResultSet rs = stmt.executeQuery("select {fn abs(-2.3)} as abs ");
    assertTrue(rs.next());
    assertEquals(2.3f, rs.getFloat(1), 0.00001);

    rs = stmt.executeQuery("select {fn acos(-0.6)} as acos ");
    assertTrue(rs.next());
    assertEquals(Math.acos(-0.6), rs.getDouble(1), 0.00001);

    rs = stmt.executeQuery("select {fn asin(-0.6)} as asin ");
    assertTrue(rs.next());
    assertEquals(Math.asin(-0.6), rs.getDouble(1), 0.00001);

    rs = stmt.executeQuery("select {fn atan(-0.6)} as atan ");
    assertTrue(rs.next());
    assertEquals(Math.atan(-0.6), rs.getDouble(1), 0.00001);

    rs = stmt.executeQuery("select {fn atan2(-2.3,7)} as atan2 ");
    assertTrue(rs.next());
    assertEquals(Math.atan2(-2.3, 7), rs.getDouble(1), 0.00001);

    rs = stmt.executeQuery("select {fn ceiling(-2.3)} as ceiling ");
    assertTrue(rs.next());
    assertEquals(-2, rs.getDouble(1), 0.00001);

    rs = stmt.executeQuery("select {fn cos(-2.3)} as cos, {fn cot(-2.3)} as cot ");
    assertTrue(rs.next());
    assertEquals(Math.cos(-2.3), rs.getDouble(1), 0.00001);
    assertEquals(1 / Math.tan(-2.3), rs.getDouble(2), 0.00001);

    rs = stmt.executeQuery("select {fn degrees({fn pi()})} as degrees ");
    assertTrue(rs.next());
    assertEquals(180, rs.getDouble(1), 0.00001);

    rs = stmt.executeQuery("select {fn exp(-2.3)}, {fn floor(-2.3)},"
        + " {fn log(2.3)},{fn log10(2.3)},{fn mod(3,2)}");
    assertTrue(rs.next());
    assertEquals(Math.exp(-2.3), rs.getDouble(1), 0.00001);
    assertEquals(-3, rs.getDouble(2), 0.00001);
    assertEquals(Math.log(2.3), rs.getDouble(3), 0.00001);
    assertEquals(Math.log(2.3) / Math.log(10), rs.getDouble(4), 0.00001);
    assertEquals(1, rs.getDouble(5), 0.00001);

    rs = stmt.executeQuery("select {fn pi()}, {fn power(7,-2.3)},"
        + " {fn radians(-180)},{fn round(3.1294,2)}");
    assertTrue(rs.next());
    assertEquals(Math.PI, rs.getDouble(1), 0.00001);
    assertEquals(Math.pow(7, -2.3), rs.getDouble(2), 0.00001);
    assertEquals(-Math.PI, rs.getDouble(3), 0.00001);
    assertEquals(3.13, rs.getDouble(4), 0.00001);

    rs = stmt.executeQuery("select {fn sign(-2.3)}, {fn sin(-2.3)},"
        + " {fn sqrt(2.3)},{fn tan(-2.3)},{fn truncate(3.1294,2)}");
    assertTrue(rs.next());
    assertEquals(-1, rs.getInt(1));
    assertEquals(Math.sin(-2.3), rs.getDouble(2), 0.00001);
    assertEquals(Math.sqrt(2.3), rs.getDouble(3), 0.00001);
    assertEquals(Math.tan(-2.3), rs.getDouble(4), 0.00001);
    assertEquals(3.12, rs.getDouble(5), 0.00001);
  }

  @Test
  public void testStringFunctions() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery(
        "select {fn ascii(' test')},{fn char(32)}"
        + ",{fn concat('ab','cd')}"
        + ",{fn lcase('aBcD')},{fn left('1234',2)},{fn length('123 ')}"
        + ",{fn locate('bc','abc')},{fn locate('bc','abc',3)}");
    assertTrue(rs.next());
    assertEquals(32, rs.getInt(1));
    assertEquals(" ", rs.getString(2));
    assertEquals("abcd", rs.getString(3));
    assertEquals("abcd", rs.getString(4));
    assertEquals("12", rs.getString(5));
    assertEquals(3, rs.getInt(6));
    assertEquals(2, rs.getInt(7));
    assertEquals(0, rs.getInt(8));

    rs = stmt.executeQuery(
        "SELECT {fn insert('abcdef',3,2,'xxxx')}"
        + ",{fn replace('abcdbc','bc','x')}");
    assertTrue(rs.next());
    assertEquals("abxxxxef", rs.getString(1));
    assertEquals("axdx", rs.getString(2));

    rs = stmt.executeQuery(
        "select {fn ltrim(' ab')},{fn repeat('ab',2)}"
        + ",{fn right('abcde',2)},{fn rtrim('ab ')}"
        + ",{fn space(3)},{fn substring('abcd',2,2)}"
        + ",{fn ucase('aBcD')}");
    assertTrue(rs.next());
    assertEquals("ab", rs.getString(1));
    assertEquals("abab", rs.getString(2));
    assertEquals("de", rs.getString(3));
    assertEquals("ab", rs.getString(4));
    assertEquals("   ", rs.getString(5));
    assertEquals("bc", rs.getString(6));
    assertEquals("ABCD", rs.getString(7));
  }

  @Test
  public void testDateFuncWithParam() throws SQLException {
    // Prior to 8.0 there is not an interval + timestamp operator,
    // so timestampadd does not work.
    //

    PreparedStatement ps = con.prepareStatement(
        "SELECT {fn timestampadd(SQL_TSI_QUARTER, ? ,{fn now()})}, {fn timestampadd(SQL_TSI_MONTH, ?, {fn now()})} ");
    ps.setInt(1, 4);
    ps.setInt(2, 12);
    ResultSet rs = ps.executeQuery();
    assertTrue(rs.next());
    assertEquals(rs.getTimestamp(1), rs.getTimestamp(2));
  }

  @Test
  public void testDateFunctions() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("select {fn curdate()},{fn curtime()}"
        + ",{fn dayname({fn now()})}, {fn dayofmonth({fn now()})}"
        + ",{fn dayofweek({ts '2005-01-17 12:00:00'})},{fn dayofyear({fn now()})}"
        + ",{fn hour({fn now()})},{fn minute({fn now()})}"
        + ",{fn month({fn now()})}"
        + ",{fn monthname({fn now()})},{fn quarter({fn now()})}"
        + ",{fn second({fn now()})},{fn week({fn now()})}"
        + ",{fn year({fn now()})} ");
    assertTrue(rs.next());
    // ensure sunday =>1 and monday =>2
    assertEquals(2, rs.getInt(5));

    // Prior to 8.0 there is not an interval + timestamp operator,
    // so timestampadd does not work.
    //

    // second
    rs = stmt.executeQuery(
        "select {fn timestampdiff(SQL_TSI_SECOND,{fn now()},{fn timestampadd(SQL_TSI_SECOND,3,{fn now()})})} ");
    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));
    // MINUTE
    rs = stmt.executeQuery(
        "select {fn timestampdiff(SQL_TSI_MINUTE,{fn now()},{fn timestampadd(SQL_TSI_MINUTE,3,{fn now()})})} ");
    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));
    // HOUR
    rs = stmt.executeQuery(
        "select {fn timestampdiff(SQL_TSI_HOUR,{fn now()},{fn timestampadd(SQL_TSI_HOUR,3,{fn now()})})} ");
    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));
    // day
    rs = stmt.executeQuery(
        "select {fn timestampdiff(SQL_TSI_DAY,{fn now()},{fn timestampadd(SQL_TSI_DAY,-3,{fn now()})})} ");
    assertTrue(rs.next());
    assertEquals(-3, rs.getInt(1));
    // WEEK => extract week from interval is not supported by backend
    // rs = stmt.executeQuery("select {fn timestampdiff(SQL_TSI_WEEK,{fn now()},{fn
    // timestampadd(SQL_TSI_WEEK,3,{fn now()})})} ");
    // assertTrue(rs.next());
    // assertEquals(3,rs.getInt(1));
    // MONTH => backend assume there are 0 month in an interval of 92 days...
    // rs = stmt.executeQuery("select {fn timestampdiff(SQL_TSI_MONTH,{fn now()},{fn
    // timestampadd(SQL_TSI_MONTH,3,{fn now()})})} ");
    // assertTrue(rs.next());
    // assertEquals(3,rs.getInt(1));
    // QUARTER => backend assume there are 1 quater even in 270 days...
    // rs = stmt.executeQuery("select {fn timestampdiff(SQL_TSI_QUARTER,{fn now()},{fn
    // timestampadd(SQL_TSI_QUARTER,3,{fn now()})})} ");
    // assertTrue(rs.next());
    // assertEquals(3,rs.getInt(1));
    // YEAR
    // rs = stmt.executeQuery("select {fn timestampdiff(SQL_TSI_YEAR,{fn now()},{fn
    // timestampadd(SQL_TSI_YEAR,3,{fn now()})})} ");
    // assertTrue(rs.next());
    // assertEquals(3,rs.getInt(1));
  }

  @Test
  public void testSystemFunctions() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery(
        "select {fn ifnull(null,'2')}"
        + ",{fn user()} ");
    assertTrue(rs.next());
    assertEquals("2", rs.getString(1));
    assertEquals(TestUtil.getUser(), rs.getString(2));

    rs = stmt.executeQuery("select {fn database()} ");
    assertTrue(rs.next());
    assertEquals(TestUtil.getDatabase(), rs.getString(1));
  }

  @Test
  public void testWarningsAreCleared() throws SQLException {
    Statement stmt = con.createStatement();
    // Will generate a NOTICE: for primary key index creation
    stmt.execute("CREATE TEMP TABLE unused (a int primary key)");
    stmt.executeQuery("SELECT 1");
    // Executing another query should clear the warning from the first one.
    assertNull(stmt.getWarnings());
    stmt.close();
  }

  /**
   * The parser tries to break multiple statements into individual queries as required by the V3
   * extended query protocol. It can be a little overzealous sometimes and this test ensures we keep
   * multiple rule actions together in one statement.
   */
  @Test
  public void testParsingSemiColons() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute(
        "CREATE RULE r1 AS ON INSERT TO escapetest DO (DELETE FROM test_statement ; INSERT INTO test_statement VALUES (1); INSERT INTO test_statement VALUES (2); );");
    stmt.executeUpdate("INSERT INTO escapetest(ts) VALUES (NULL)");
    ResultSet rs = stmt.executeQuery("SELECT i from test_statement ORDER BY i");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    assertTrue(!rs.next());
  }

  @Test
  public void testParsingDollarQuotes() throws SQLException {
    // dollar-quotes are supported in the backend since version 8.0
    Statement st = con.createStatement();
    ResultSet rs;

    rs = st.executeQuery("SELECT '$a$ ; $a$'");
    assertTrue(rs.next());
    assertEquals("$a$ ; $a$", rs.getObject(1));
    rs.close();

    rs = st.executeQuery("SELECT $$;$$");
    assertTrue(rs.next());
    assertEquals(";", rs.getObject(1));
    rs.close();

    rs = st.executeQuery("SELECT $OR$$a$'$b$a$$OR$ WHERE '$a$''$b$a$'=$OR$$a$'$b$a$$OR$OR ';'=''");
    assertTrue(rs.next());
    assertEquals("$a$'$b$a$", rs.getObject(1));
    assertFalse(rs.next());
    rs.close();

    rs = st.executeQuery("SELECT $B$;$b$B$");
    assertTrue(rs.next());
    assertEquals(";$b", rs.getObject(1));
    rs.close();

    rs = st.executeQuery("SELECT $c$c$;$c$");
    assertTrue(rs.next());
    assertEquals("c$;", rs.getObject(1));
    rs.close();

    rs = st.executeQuery("SELECT $A0$;$A0$ WHERE ''=$t$t$t$ OR ';$t$'=';$t$'");
    assertTrue(rs.next());
    assertEquals(";", rs.getObject(1));
    assertFalse(rs.next());
    rs.close();

    st.executeQuery("SELECT /* */$$;$$/**//*;*/").close();
    st.executeQuery("SELECT /* */--;\n$$a$$/**/--\n--;\n").close();

    st.close();
  }

  @Test
  public void testUnbalancedParensParseError() throws SQLException {
    Statement stmt = con.createStatement();
    try {
      stmt.executeQuery("SELECT i FROM test_statement WHERE (1 > 0)) ORDER BY i");
      fail("Should have thrown a parse error.");
    } catch (SQLException sqle) {
    }
  }

  @Test
  public void testExecuteUpdateFailsOnSelect() throws SQLException {
    Statement stmt = con.createStatement();
    try {
      stmt.executeUpdate("SELECT 1");
      fail("Should have thrown an error.");
    } catch (SQLException sqle) {
    }
  }

  @Test
  public void testExecuteUpdateFailsOnMultiStatementSelect() throws SQLException {
    Statement stmt = con.createStatement();
    try {
      stmt.executeUpdate("/* */; SELECT 1");
      fail("Should have thrown an error.");
    } catch (SQLException sqle) {
    }
  }

  @Test
  public void testSetQueryTimeout() throws SQLException {
    Statement stmt = con.createStatement();
    long start = 0;
    boolean cancelReceived = false;
    try {
      stmt.setQueryTimeout(1);
      start = System.currentTimeMillis();
      stmt.execute("select pg_sleep(10)");
    } catch (SQLException sqle) {
      // state for cancel
      if ("57014".equals(sqle.getSQLState())) {
        cancelReceived = true;
      }
    }
    long duration = System.currentTimeMillis() - start;
    if (!cancelReceived || duration > 5000) {
      fail("Query should have been cancelled since the timeout was set to 1 sec."
          + " Cancel state: " + cancelReceived + ", duration: " + duration);
    }
  }

  /**
   * Test executes two queries one after another. The first one has timeout of 1ms, and the second
   * one does not. The timeout of the first query should not impact the second one.
   */
  @Test
  public void testShortQueryTimeout() throws SQLException {
    long deadLine = System.currentTimeMillis() + 10000;
    Statement stmt = con.createStatement();
    ((PgStatement) stmt).setQueryTimeoutMs(1);
    Statement stmt2 = con.createStatement();
    while (System.currentTimeMillis() < deadLine) {
      try {
        stmt.execute("select 1");
      } catch (SQLException e) {
        // ignore "statement cancelled"
      }
      stmt2.executeQuery("select 1");
    }
  }

  @Test
  public void testSetQueryTimeoutWithSleep() throws SQLException, InterruptedException {
    // check that the timeout starts ticking at execute, not at the
    // setQueryTimeout call.
    Statement stmt = con.createStatement();
    try {
      stmt.setQueryTimeout(1);
      Thread.sleep(3000);
      stmt.execute("select pg_sleep(5)");
      fail("statement should have been canceled by query timeout");
    } catch (SQLException sqle) {
      // state for cancel
      if (sqle.getSQLState().compareTo("57014") != 0) {
        throw sqle;
      }
    }
  }

  @Test
  public void testSetQueryTimeoutOnPrepared() throws SQLException, InterruptedException {
    // check that a timeout set on a prepared statement works on every
    // execution.
    PreparedStatement pstmt = con.prepareStatement("select pg_sleep(5)");
    pstmt.setQueryTimeout(1);
    for (int i = 1; i <= 3; i++) {
      try {
        ResultSet rs = pstmt.executeQuery();
        fail("statement should have been canceled by query timeout (execution #" + i + ")");
      } catch (SQLException sqle) {
        // state for cancel
        if (sqle.getSQLState().compareTo("57014") != 0) {
          throw sqle;
        }
      }
    }
  }

  @Test
  public void testSetQueryTimeoutWithoutExecute() throws SQLException, InterruptedException {
    // check that a timeout set on one statement doesn't affect another
    Statement stmt1 = con.createStatement();
    stmt1.setQueryTimeout(1);

    Statement stmt2 = con.createStatement();
    ResultSet rs = stmt2.executeQuery("SELECT pg_sleep(2)");
  }

  @Test
  public void testResultSetTwice() throws SQLException {
    Statement stmt = con.createStatement();

    ResultSet rs = stmt.executeQuery("select {fn abs(-2.3)} as abs ");
    assertNotNull(rs);

    ResultSet rsOther = stmt.getResultSet();
    assertNotNull(rsOther);
  }

  @Test
  public void testMultipleCancels() throws Exception {
    org.postgresql.util.SharedTimer sharedTimer = org.postgresql.Driver.getSharedTimer();

    Connection connA = null;
    Connection connB = null;
    Statement stmtA = null;
    Statement stmtB = null;
    ResultSet rsA = null;
    ResultSet rsB = null;
    try {
      assertEquals(0, sharedTimer.getRefCount());
      connA = TestUtil.openDB();
      connB = TestUtil.openDB();
      stmtA = connA.createStatement();
      stmtB = connB.createStatement();
      stmtA.setQueryTimeout(1);
      stmtB.setQueryTimeout(1);
      try {
        rsA = stmtA.executeQuery("SELECT pg_sleep(2)");
      } catch (SQLException e) {
        // ignore the expected timeout
      }
      assertEquals(1, sharedTimer.getRefCount());
      try {
        rsB = stmtB.executeQuery("SELECT pg_sleep(2)");
      } catch (SQLException e) {
        // ignore the expected timeout
      }
    } finally {
      TestUtil.closeQuietly(rsA);
      TestUtil.closeQuietly(rsB);
      TestUtil.closeQuietly(stmtA);
      TestUtil.closeQuietly(stmtB);
      TestUtil.closeQuietly(connA);
      TestUtil.closeQuietly(connB);
    }
    assertEquals(0, sharedTimer.getRefCount());
  }

  /**
   * Tests that calling {@code java.sql.Statement#close()} from a concurrent thread does not result
   * in {@link java.util.ConcurrentModificationException}
   */
  @Test
  public void testSideStatementFinalizers() throws SQLException {
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(2);

    final AtomicInteger leaks = new AtomicInteger();
    final AtomicReference<Throwable> cleanupFailure = new AtomicReference<Throwable>();

    for (int q = 0; System.currentTimeMillis() < deadline || leaks.get() < 10000; q++) {
      for (int i = 0; i < 100; i++) {
        PreparedStatement ps = con.prepareStatement("select " + (i + q));
        ps.close();
      }
      final int nextId = q;
      new Object() {
        PreparedStatement ps = con.prepareStatement("select /*leak*/ " + nextId);

        @Override
        protected void finalize() throws Throwable {
          super.finalize();
          try {
            ps.close();
          } catch (Throwable t) {
            cleanupFailure.compareAndSet(null, t);
          }
          leaks.incrementAndGet();
        }
      };
    }
    if (cleanupFailure.get() != null) {
      throw new IllegalStateException("Detected failure in cleanup thread", cleanupFailure.get());
    }
  }

  /**
   * Test that $JAVASCRIPT$ protects curly braces from JDBC {fn now()} kind of syntax.
   * @throws SQLException if something goes wrong
   */
  @Test
  public void testJavascriptFunction() throws SQLException {
    String str = "  var _modules = {};\n"
        + "  var _current_stack = [];\n"
        + "\n"
        + "  // modules start\n"
        + "  _modules[\"/root/aidbox/fhirbase/src/core\"] = {\n"
        + "  init:  function(){\n"
        + "    var exports = {};\n"
        + "    _current_stack.push({file: \"core\", dir: \"/root/aidbox/fhirbase/src\"})\n"
        + "    var module = {exports: exports};";

    PreparedStatement ps = null;
    try {
      ps = con.prepareStatement("select $JAVASCRIPT$" + str + "$JAVASCRIPT$");
      ResultSet rs = ps.executeQuery();
      rs.next();
      assertEquals("Javascript code has been protected with $JAVASCRIPT$", str, rs.getString(1));
    } finally {
      TestUtil.closeQuietly(ps);
    }
  }

  @Test
  public void testUnterminatedDollarQuotes() throws SQLException {
    ensureSyntaxException("dollar quotes", "CREATE OR REPLACE FUNCTION update_on_change() RETURNS TRIGGER AS $$\n"
        + "BEGIN");
  }

  @Test
  public void testUnterminatedNamedDollarQuotes() throws SQLException {
    ensureSyntaxException("dollar quotes", "CREATE OR REPLACE FUNCTION update_on_change() RETURNS TRIGGER AS $ABC$\n"
        + "BEGIN");
  }

  @Test
  public void testUnterminatedComment() throws SQLException {
    ensureSyntaxException("block comment", "CREATE OR REPLACE FUNCTION update_on_change() RETURNS TRIGGER AS /* $$\n"
        + "BEGIN $$");
  }

  @Test
  public void testUnterminatedLiteral() throws SQLException {
    ensureSyntaxException("string literal", "CREATE OR REPLACE FUNCTION update_on_change() 'RETURNS TRIGGER AS $$\n"
        + "BEGIN $$");
  }

  @Test
  public void testUnterminatedIdentifier() throws SQLException {
    ensureSyntaxException("string literal", "CREATE OR REPLACE FUNCTION \"update_on_change() RETURNS TRIGGER AS $$\n"
        + "BEGIN $$");
  }

  private void ensureSyntaxException(String errorType, String sql) throws SQLException {
    PreparedStatement ps = null;
    try {
      ps = con.prepareStatement(sql);
      ps.executeUpdate();
      fail("Query with unterminated " + errorType + " should fail");
    } catch (SQLException e) {
      assertEquals("Query should fail with unterminated " + errorType,
          PSQLState.SYNTAX_ERROR.getState(), e.getSQLState());
    } finally {
      TestUtil.closeQuietly(ps);
    }
  }
}
