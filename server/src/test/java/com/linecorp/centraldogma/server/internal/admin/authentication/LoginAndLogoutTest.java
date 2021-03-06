/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.centraldogma.server.internal.admin.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.concurrent.TimeUnit;

import org.apache.shiro.config.Ini;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.centraldogma.internal.Jackson;
import com.linecorp.centraldogma.internal.api.v1.AccessToken;
import com.linecorp.centraldogma.server.CentralDogmaBuilder;
import com.linecorp.centraldogma.testing.CentralDogmaRule;

public class LoginAndLogoutTest {

    static final String USERNAME = "foo";
    static final String PASSWORD = "bar";
    static final String WRONG_PASSWORD = "baz";
    static final String WRONG_SESSION_ID = "00000000-0000-0000-0000-000000000000";

    private static final Encoder encoder = Base64.getEncoder();

    static Ini newSecurityConfig() {
        final Ini ini = new Ini();
        ini.addSection("users").put(USERNAME, PASSWORD);
        return ini;
    }

    static AggregatedHttpMessage login(HttpClient client, String username, String password) {
        return client.execute(
                HttpHeaders.of(HttpHeaderNames.METHOD, "POST",
                               HttpHeaderNames.PATH, "/api/v1/login",
                               HttpHeaderNames.CONTENT_TYPE, MediaType.FORM_DATA.toString()),
                "grant_type=password&username=" + username + "&password=" + password,
                StandardCharsets.US_ASCII).aggregate().join();
    }

    private static AggregatedHttpMessage loginWithBasicAuth(HttpClient client) {
        return client.execute(
                HttpHeaders.of(HttpHeaderNames.METHOD, "POST",
                               HttpHeaderNames.PATH, "/api/v1/login",
                               HttpHeaderNames.AUTHORIZATION, "basic " + encoder.encodeToString(
                                       (USERNAME + ':' + PASSWORD).getBytes(StandardCharsets.US_ASCII))))
                   .aggregate().join();
    }

    static AggregatedHttpMessage logout(HttpClient client, String sessionId) {
        return client.execute(
                HttpHeaders.of(HttpHeaderNames.METHOD, "POST",
                               HttpHeaderNames.PATH, "/api/v1/logout",
                               HttpHeaderNames.AUTHORIZATION,
                               "bearer " + sessionId)).aggregate().join();
    }

    static AggregatedHttpMessage usersMe(HttpClient client, String sessionId) {
        return client.execute(
                HttpHeaders.of(HttpHeaderNames.METHOD, "GET",
                               HttpHeaderNames.PATH, "/api/v0/users/me",
                               HttpHeaderNames.AUTHORIZATION,
                               "bearer " + sessionId)).aggregate().join();
    }

    @Rule
    public final CentralDogmaRule rule = new CentralDogmaRule() {
        @Override
        protected void configure(CentralDogmaBuilder builder) {
            builder.securityConfig(newSecurityConfig());
            builder.webAppEnabled(true);
        }
    };

    private HttpClient client;

    @Before
    public void setClient() {
        client = rule.httpClient();
    }

    @Test
    public void password() throws Exception { // grant_type=password
        loginAndLogout(login(client, USERNAME, PASSWORD));
    }

    private void loginAndLogout(AggregatedHttpMessage loginRes) throws Exception {
        assertThat(loginRes.status()).isEqualTo(HttpStatus.OK);

        // Ensure authorization works.
        final AccessToken accessToken = Jackson.readValue(loginRes.content().toStringUtf8(), AccessToken.class);
        final String sessionId = accessToken.accessToken();
        assertThat(usersMe(client, sessionId).status()).isEqualTo(HttpStatus.OK);

        // Log out.
        assertThat(logout(client, sessionId).status()).isEqualTo(HttpStatus.OK);
        assertThat(usersMe(client, sessionId).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    public void consecutiveLoginShouldResponseSameToken() throws Exception {
        final AggregatedHttpMessage res1 = login(client, USERNAME, PASSWORD);
        TimeUnit.MILLISECONDS.sleep(100); // Sleep a little bit to get a response with different expiresIn.
        final AggregatedHttpMessage res2 = login(client, USERNAME, PASSWORD);
        final AccessToken token1 = Jackson.readValue(res1.content().array(), AccessToken.class);
        final AccessToken token2 = Jackson.readValue(res2.content().array(), AccessToken.class);
        assertThat(token1.accessToken()).isEqualTo(token2.accessToken());
        assertThat(token1.expiresIn()).isGreaterThan(token2.expiresIn());
    }

    @Test
    public void basicAuth() throws Exception {
        loginAndLogout(loginWithBasicAuth(client));
    }

    @Test
    public void incorrectLogin() throws Exception {
        assertThat(login(client, USERNAME, WRONG_PASSWORD).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    public void incorrectLogout() throws Exception {
        assertThat(logout(client, WRONG_SESSION_ID).status()).isEqualTo(HttpStatus.OK);
    }
}
