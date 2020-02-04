/*
 * Copyright 2017 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.origin.httpserver;

import com.streamsets.pipeline.api.Config;
import com.streamsets.pipeline.api.credential.CredentialValue;
import com.streamsets.pipeline.config.upgrade.UpgraderTestUtils;
import com.streamsets.pipeline.config.upgrade.UpgraderUtils;
import com.streamsets.pipeline.lib.httpsource.CredentialValueBean;
import com.streamsets.pipeline.stage.util.tls.TlsConfigBeanUpgraderTestUtil;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class TestHttpServerPushSourceUpgrader {

  @Test
  public void testV1ToV2() throws Exception {
    TlsConfigBeanUpgraderTestUtil.testRawKeyStoreConfigsToTlsConfigBeanUpgrade(
        "httpConfigs.",
        new HttpServerPushSourceUpgrader(),
        10
    );
  }

  @Test
  public void testV10ToV11() throws Exception {
    HttpServerPushSourceUpgrader upgrader = new HttpServerPushSourceUpgrader();
    List<Config> configs = new ArrayList<>();
    String idValue = "idFooo";
    configs.add(new Config("httpConfigs.appId", (CredentialValue) () -> idValue));
    upgrader.upgrade("", "", "", 10, 11, configs);
    UpgraderTestUtils.assertNoneExist(configs,"httpConfigs.appId");
    UpgraderTestUtils.assertAllExist(configs, "httpConfigs.appIds");
    Config configWithName = UpgraderUtils.getConfigWithName(configs, "httpConfigs.appIds");
    List<CredentialValueBean> listCredentials = (List<CredentialValueBean>) configWithName.getValue();
    Assert.assertEquals(listCredentials.size(), 1);
    Assert.assertEquals(listCredentials.get(0).get().equals(idValue), true);
  }

}
