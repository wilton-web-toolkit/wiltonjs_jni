/*
 * Copyright 2016, alex at staticlibs.net
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.BeforeClass;
import org.junit.Test;
import utils.TestGateway;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static wilton.WiltonJni.wiltoncall;
import static org.junit.Assert.assertTrue;
import static utils.TestUtils.*;

/**
 * User: alexkasko
 * Date: 9/7/16
 */
public class CronJniTest {

    @BeforeClass
    public static void init() {
        // init, no logging by default, enable it when needed
        initWiltonOnce(new TestGateway(), LOGGING_DISABLE);
    }

    @Test
    public void test() throws Exception {
        String out = wiltoncall("cron_start", GSON.toJson(ImmutableMap.builder()
                .put("expression", "* * * * * *") // every second
                .put("callbackScript", ImmutableMap.builder()
                        .put("module", "cron/test")
                        .put("func", "test")
                        .put("args", ImmutableList.of())
                        .build())
                .build()));
        Map<String, Long> shamap = GSON.fromJson(out, LONG_MAP_TYPE);
        long handle = shamap.get("cronHandle");
        assertEquals(0, TestGateway.cronCounter.get());
        // slow, uncomment for re-test
//        Thread.sleep(2100);
//        System.out.println(TestGateway.cronCounter.get());
//        assertTrue(2 == TestGateway.cronCounter.get() || 3 == TestGateway.cronCounter.get());
        wiltoncall("cron_stop", GSON.toJson(ImmutableMap.builder()
                .put("cronHandle", handle)
                .build()));
        int finalres = TestGateway.cronCounter.get();
//        System.out.println(finalres);
//        Thread.sleep(2100);
//        System.out.println(TestGateway.cronCounter.get());
        assertEquals(finalres, TestGateway.cronCounter.get());
    }

}
