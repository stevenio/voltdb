/* This file is part of VoltDB.
 * Copyright (C) 2008-2015 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb.regressionsuites;

import junit.framework.Test;

import org.voltdb.TheHashinator;
import org.voltdb.VoltTable;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.VoltType;
import org.voltdb.client.Client;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ProcCallException;
import org.voltdb.compiler.CatalogBuilder;
import org.voltdb.compiler.DeploymentBuilder;

public class TestLoadingSuite extends RegressionSuite {

    static VoltTable m_template = new VoltTable(new ColumnInfo[] {
            new ColumnInfo("col1", VoltType.BIGINT),
            new ColumnInfo("col2", VoltType.BIGINT),
            new ColumnInfo("col3", VoltType.BIGINT),
            new ColumnInfo("col4", VoltType.STRING),
            new ColumnInfo("col5", VoltType.FLOAT)
    });

    public long countPartitionedRows(Client client) throws Exception {
        ClientResponse r = client.callProcedure("@AdHoc", "select count(*) from PARTITIONED");
        assertEquals(ClientResponse.SUCCESS, r.getStatus());
        assertEquals(1, r.getResults().length);
        return r.getResults()[0].asScalarLong();
    }

    public long countReplicatedRows(Client client) throws Exception {
        ClientResponse r = client.callProcedure("@AdHoc", "select count(*) from REPLICATED");
        assertEquals(ClientResponse.SUCCESS, r.getStatus());
        assertEquals(1, r.getResults().length);
        return r.getResults()[0].asScalarLong();
    }

    public void testSinglePartitionLoad() throws Exception {

        Client client = getClient();
        VoltTable table; ClientResponse r;

        // test simple success
        table = m_template.clone(100);
        table.addRow(1, 1, 1, "1", 1.0);
        table.addRow(2, 1, 2, "2", 2.0);
        r = client.callProcedure("@LoadSinglepartitionTable", TheHashinator.valueToBytes(1),
                "PARTITIONED", table);
        assertEquals(ClientResponse.SUCCESS, r.getStatus());
        assertEquals(1, r.getResults().length);
        assertEquals(2, r.getResults()[0].asScalarLong());
        assertEquals(2, countPartitionedRows(client));

        // test failure to load replicated table from SP proc
        try {
            r = client.callProcedure("@LoadSinglepartitionTable", TheHashinator.valueToBytes(1),
                    "REPLICATED", table);
            fail(); // prev stmt should throw exception
        } catch (ProcCallException e) {
            e.printStackTrace();
        }
        assertEquals(0, countReplicatedRows(client));

        // test rollback for constraint
        table = m_template.clone(100);
        table.addRow(3, 2, 3, "3", 3.0);
        table.addRow(3, 2, 3, "3", 3.0);
        try {
            r = client.callProcedure("@LoadSinglepartitionTable", TheHashinator.valueToBytes(2),
                    "PARTITIONED", table);
            fail(); // prev stmt should throw exception
        } catch (ProcCallException e) {
            e.printStackTrace();
        }
        // 2 rows in the db from the previous test (3 for hsql)
        if (isHSQL()) // sadly, hsql is not super transactional as a backend
            assertEquals(3, countPartitionedRows(client));
        else
            assertEquals(2, countPartitionedRows(client));
    }

    public void testMultiPartitionLoad() throws Exception {

        Client client = getClient();
        VoltTable table; ClientResponse r;

        // test successful load to replicated table from MP txn
        table = m_template.clone(100);
        table.addRow(1, 1, 1, "1", 1.0);
        table.addRow(2, 1, 2, "2", 2.0);
        table.addRow(3, 2, 3, "3", 3.0);
        table.addRow(4, 2, 4, "4", 4.0);
        r = client.callProcedure("@LoadMultipartitionTable", "REPLICATED", table);
        assertEquals(ClientResponse.SUCCESS, r.getStatus());
        assertEquals(1, r.getResults().length);
        assertEquals(4, r.getResults()[0].asScalarLong());
        assertEquals(4, countReplicatedRows(client));

        if (!isHSQL()) {
            // test successful load to partitioned table from MP txn
            table = m_template.clone(100);
            table.addRow(1, 1, 1, "1", 1.0);
            table.addRow(2, 1, 2, "2", 2.0);
            table.addRow(3, 2, 3, "3", 3.0);
            table.addRow(4, 2, 4, "4", 4.0);
            try {
                r = client.callProcedure("@LoadMultipartitionTable", "PARTITIONED", table);
                fail();
            } catch (ProcCallException e) {}

            // test rollback to a replicated table (constraint)
            table = m_template.clone(100);
            table.addRow(5, 1, 5, "5", 5.0);
            table.addRow(5, 1, 5, "5", 5.0);
            try {
                r = client.callProcedure("@LoadMultipartitionTable", "REPLICATED", table);
                fail(); // prev stmt should throw exception
            } catch (ProcCallException e) {}
            // 4 rows in the db from the previous test
            assertEquals(4, countReplicatedRows(client));
        }
    }

    public TestLoadingSuite(String name) {
        super(name);
    }

    /**
     * Build a list of the tests that will be run when this suite gets run by JUnit.
     * Use helper classes that are part of the RegressionSuite framework.
     * This particular class runs all tests on the the local JNI backend with both
     * one and two partition configurations, as well as on the hsql backend.
     *
     * @return The TestSuite containing all the tests to be run.
     */
    static public Test suite() {
        CatalogBuilder cb = new CatalogBuilder(
                "CREATE TABLE REPLICATED (\n" +
                "  ival INTEGER DEFAULT '0' NOT NULL,\n" +
                "  pval INTEGER DEFAULT '0' NOT NULL,\n" +
                "  bval TINYINT DEFAULT '0' NOT NULL,\n" +
                "  sval VARCHAR(60) DEFAULT '0' NOT NULL,\n" +
                "  dval FLOAT DEFAULT '0' NOT NULL,\n" +
                "  PRIMARY KEY (ival)\n" +
                ");\n" +
                "CREATE TABLE PARTITIONED (\n" +
                "  ival INTEGER DEFAULT '0' NOT NULL,\n" +
                "  pval INTEGER DEFAULT '0' NOT NULL,\n" +
                "  bval TINYINT DEFAULT '0' NOT NULL,\n" +
                "  sval VARCHAR(60) DEFAULT '0' NOT NULL,\n" +
                "  dval FLOAT DEFAULT '0' NOT NULL,\n" +
                "  PRIMARY KEY (ival,pval)\n" +
                ");\n" +
                "PARTITION TABLE PARTITIONED ON COLUMN pval;\n" +
                "")
        .addStmtProcedure("dummy", "select * from REPLICATED;")
        ;
        return multiClusterSuiteBuilder(TestLoadingSuite.class, cb,
                // CONFIG #1: 2 Local Site/Partitions running on JNI backend
                new DeploymentBuilder(2),
                // CONFIG #2: HSQLDB
                DeploymentBuilder.forHSQLBackend(),
                // CONFIG #3: Local Cluster (of processes)
                new DeploymentBuilder(2, 3, 1));
    }
}
