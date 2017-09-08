import com.google.common.collect.ImmutableMap;
import net.wiltontoolkit.support.rhino.WiltonRhinoEnvironment;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mozilla.javascript.*;
import utils.TestGateway;
import utils.TestUtils;

import static net.wiltontoolkit.WiltonJni.LOGGING_DISABLE;
import static org.apache.commons.io.FileUtils.readFileToString;
import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.apache.commons.io.IOUtils.toString;
import static org.junit.Assert.assertEquals;
import static utils.TestUtils.GSON;
import static utils.TestUtils.getJsDir;
import static utils.TestUtils.initWiltonOnce;

/**
 * User: alexkasko
 * Date: 5/15/16
 */
public class WiltonRhinoTest {

    @BeforeClass
    public static void init() {
        String wiltonDirPath = getJsDir().getAbsolutePath();
        // init, no logging by default, enable it when needed
        initWiltonOnce(new TestGateway(), LOGGING_DISABLE, wiltonDirPath);
        WiltonRhinoEnvironment.initialize(wiltonDirPath);
        TestGateway tg = (TestGateway) TestUtils.GATEWAY;
        tg.setScriptGateway(WiltonRhinoEnvironment.gateway());
    }

    @Test
    public void test() throws Exception {
        // wilton test suite
        WiltonRhinoEnvironment.gateway().runScript(GSON.toJson(ImmutableMap.builder()
                .put("module", "test/scripts/runWiltonTests")
                .put("func", "main")
                .build()));
        // node modules tests, overflows default stack on 32-bit
        Thread th = new Thread(new ThreadGroup("js"), new Runnable() {
            public void run() {
                try {
                    WiltonRhinoEnvironment.gateway().runScript(GSON.toJson(ImmutableMap.builder()
                            .put("module", "test/scripts/runNodeTests")
                            .put("func", "")
                            .build()));
                } catch(Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }, "WiltonRhinoTest", 1024 * 1024 * 16);
        th.start();
        th.join();
    }

    @Test
    // https://github.com/mozilla/rhino/issues/153
    public void testStacktrace() throws Exception {
        Scriptable scope = WiltonRhinoEnvironment.threadScope();
        Context cx = Context.enter();
        Object[] holder = new Object[1];
        ScriptableObject.putProperty(scope, "holder", Context.javaToJS(holder, scope));
        cx.evaluateString(scope, "try {throw new Error() } catch (e) {holder[0] = e.stack}", "some_file.js", 42, null);
        assertEquals("\tat some_file.js:42" + System.lineSeparator(), holder[0]);
        Context.exit();
    }
}
