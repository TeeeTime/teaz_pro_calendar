package pro.teaz.calendar.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"prod", "test"})
class SecurityConfigTests {

	@Autowired
	MockMvc mvc;

	@Test
	void rejectsRequestWithoutToken() throws Exception {
		mvc.perform(get("/api/account")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/events?from=2026-10-19&to=2026-10-26")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/events/today")).andExpect(status().isUnauthorized());
	}

	@Test
	void anyLoggedInUserMayUseIt() throws Exception {
		mvc.perform(get("/api/account").with(jwt())).andExpect(status().isOk());
		mvc.perform(get("/api/events?from=2026-10-19&to=2026-10-26").with(jwt())).andExpect(status().isOk());
	}

	@Test
	void onlyTheTileEndpointIsSharedCrossOrigin() throws Exception {
		mvc.perform(options("/api/events/today")
				.header("Origin", "https://portal.teaz.pro")
				.header("Access-Control-Request-Method", "GET")
				.header("Access-Control-Request-Headers", "Authorization"))
			.andExpect(status().isOk())
			.andExpect(header().string("Access-Control-Allow-Origin", "https://portal.teaz.pro"));

		mvc.perform(options("/api/events")
				.header("Origin", "https://portal.teaz.pro")
				.header("Access-Control-Request-Method", "GET"))
			.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
		mvc.perform(options("/api/account")
				.header("Origin", "https://portal.teaz.pro")
				.header("Access-Control-Request-Method", "GET"))
			.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
		mvc.perform(options("/api/events/today")
				.header("Origin", "https://evil.example")
				.header("Access-Control-Request-Method", "GET"))
			.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}
}
