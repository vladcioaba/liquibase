package liquibase.ext.ora.renametrigger;

import liquibase.ext.ora.testing.BaseTestCase;
import org.junit.Before;
import org.junit.Test;

public class RenameTriggerDBTest extends BaseTestCase {

    @Test
    public void placeholder() {

    }

//    private IDataSet loadedDataSet;
//    private final String TABLE_NAME = "USER_TRIGGERS";
//
//    @Before
//    public void setUp() throws Exception {
//        changeLogFile = "liquibase/ext/ora/renametrigger/changelog.test.xml";
//        connectToDB();
//        cleanDB();
//    }
//
//    protected IDatabaseConnection getConnection() throws Exception {
//        return new DatabaseConnection(connection);
//    }
//
//    protected IDataSet getDataSet() throws Exception {
//        loadedDataSet = new FlatXmlDataSet(this.getClass().getClassLoader().getResourceAsStream(
//                "liquibase/ext/ora/renametrigger/input.xml"));
//        return loadedDataSet;
//    }
//
//    @Test
//    public void testCompare() throws Exception {
//        if (connection == null) {
//            return;
//        }
//        QueryDataSet actualDataSet = new QueryDataSet(getConnection());
//
//        liquiBase.update((String) null);
//        actualDataSet.addTable("USER_TRIGGERS", "SELECT TRIGGER_NAME from " + TABLE_NAME
//                + " WHERE table_name = 'TRIGGERTEST'");
//        loadedDataSet = getDataSet();
//
//        Assertion.assertEquals(loadedDataSet, actualDataSet);
//    }
}