package org.olf.dcb.core.interaction.sierra;

import static java.util.Collections.emptyList;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasProperty;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.olf.dcb.test.PublisherUtils.singleValueFrom;
import static org.olf.dcb.test.matchers.interaction.HttpResponseProblemMatchers.hasHttpVersion;
import static org.olf.dcb.test.matchers.interaction.HttpResponseProblemMatchers.hasJsonResponseBodyProperty;
import static org.olf.dcb.test.matchers.interaction.HttpResponseProblemMatchers.hasMessageForRequest;
import static org.olf.dcb.test.matchers.interaction.HttpResponseProblemMatchers.hasRequestMethod;
import static org.olf.dcb.test.matchers.interaction.HttpResponseProblemMatchers.hasRequestUrl;
import static org.olf.dcb.test.matchers.interaction.HttpResponseProblemMatchers.hasResponseStatusCode;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockserver.client.MockServerClient;
import org.olf.dcb.core.interaction.HostLmsRenewal;
import org.olf.dcb.test.HostLmsFixture;
import org.zalando.problem.ThrowableProblem;

import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import services.k_int.interaction.sierra.CheckoutEntry;
import services.k_int.interaction.sierra.SierraTestUtils;
import services.k_int.test.mockserver.MockServerMicronautTest;

import java.util.List;

@Slf4j
@MockServerMicronautTest
@TestInstance(PER_CLASS)
class SierraHostLmsClientRenewalTests {
	private static final String CIRCULATING_HOST_LMS_CODE = "sierra-item-circulating";
	private static final String BASE_URL = "https://renewal-api-tests.com";

	@Inject private SierraApiFixtureProvider sierraApiFixtureProvider;
	@Inject private HostLmsFixture hostLmsFixture;

	private SierraItemsAPIFixture sierraItemsAPIFixture;
	private SierraPatronsAPIFixture sierraPatronsAPIFixture;

	@BeforeAll
	public void beforeAll(MockServerClient mockServerClient) {
		final String TOKEN = "test-token";
		final String KEY = "renewal-key";
		final String SECRET = "renewal-secret";

		SierraTestUtils.mockFor(mockServerClient, BASE_URL)
			.setValidCredentials(KEY, SECRET, TOKEN, 3600);

		sierraItemsAPIFixture = sierraApiFixtureProvider.items(mockServerClient, null);
		sierraPatronsAPIFixture = sierraApiFixtureProvider.patrons(mockServerClient, null);

		final var sierraLoginFixture = sierraApiFixtureProvider.login(mockServerClient, null);

		sierraLoginFixture.failLoginsForAnyOtherCredentials(KEY, SECRET);

		hostLmsFixture.deleteAll();

		hostLmsFixture.createSierraHostLms(CIRCULATING_HOST_LMS_CODE, KEY, SECRET, BASE_URL, "item");
	}

	@Test
	void shouldFetchItemCheckouts() {
		// Arrange
		final var localItemId = "10942942";
		final var localPatronId = "1182843";
		final var localItemBarcode = "98030205213515";
		final var localPatronBarcode = "9821734";

		final var hostLmsRenewal = HostLmsRenewal.builder()
			.localItemId(localItemId)
			.localPatronId(localPatronId)
			.localItemBarcode(localItemBarcode)
			.localPatronBarcode(localPatronBarcode)
			.build();

		final var checkoutId = "4983755";

		final var checkout = CheckoutEntry.builder()
			.id(toSierraUrl("/patrons/checkouts/%s".formatted(checkoutId)))
			.patron(toSierraUrl("/patrons/%s".formatted(localPatronId)))
			.item(toSierraUrl("/items/%s".formatted(localItemId)))
			.barcode(localItemBarcode)
			.build();

		sierraItemsAPIFixture.checkoutsForItem(localItemId, checkout);
		sierraPatronsAPIFixture.mockRenewalSuccess(checkoutId, checkout);

		// Act
		final var client = hostLmsFixture.createClient(CIRCULATING_HOST_LMS_CODE);

		final var response = singleValueFrom(client.renew(hostLmsRenewal));

		// Assert
		assertThat(response, is(notNullValue()));
		assertThat(response, allOf(
			hasProperty("localItemId", is(localItemId)),
			hasProperty("localPatronId", is(localPatronId)),
			hasProperty("localItemBarcode", is(localItemBarcode)),
			hasProperty("localPatronBarcode", is(localPatronBarcode))
		));
	}

	@Test
	void shouldReturnProblemWhenNoCheckoutRecordsAreFound() {
		// Arrange
		final var itemId = "10942942";
		final var patronId = "1182843";
		final var itemBarcode = "98030205213515";
		final var patronBarcode = "9821734";

		final var hostLmsRenewal = HostLmsRenewal.builder()
			.localItemId(itemId)
			.localPatronId(patronId)
			.localItemBarcode(itemBarcode)
			.localPatronBarcode(patronBarcode)
			.build();

		sierraItemsAPIFixture.checkoutsForItem(itemId, emptyList());

		// Act
		final var client = hostLmsFixture.createClient(CIRCULATING_HOST_LMS_CODE);

		final var problem = assertThrows(ThrowableProblem.class,
			() -> singleValueFrom(client.renew(hostLmsRenewal)));

		// Assert
		assertThat(problem, allOf(
			hasProperty("title", is("Checkout ID not found for renewal")),
			hasProperty("detail", is("No checkout records returned"))
		));
	}

	@Test
	void shouldReturnProblemWhenNoCheckoutsMatchPatronId() {
		// Arrange
		final var itemId = "10942942";
		final var patronId = "34273984";
		final var itemBarcode = "98030205213515";
		final var patronBarcode = "9821734";

		final var hostLmsRenewal = HostLmsRenewal.builder()
			.localItemId(itemId)
			.localPatronId(patronId)
			.localItemBarcode(itemBarcode)
			.localPatronBarcode(patronBarcode)
			.build();

		sierraItemsAPIFixture.checkoutsForItem(itemId,
			CheckoutEntry.builder()
				.id("3857476")
				// Use different patron id
				.patron("97858345")
				.build());

		// Act
		final var client = hostLmsFixture.createClient(CIRCULATING_HOST_LMS_CODE);

		final var problem = assertThrows(ThrowableProblem.class,
			() -> singleValueFrom(client.renew(hostLmsRenewal)));

		// Assert
		assertThat(problem, allOf(
			hasProperty("title", is("Checkout ID not found for renewal")),
			hasProperty("detail", is("No checkouts matching local patron id found"))
		));
	}

	@Test
	void shouldReturnProblemWhenMultipleCheckoutsMatchPatronId() {
		// Arrange
		final var itemId = "10942942";
		final var patronId = "1182843";
		final var itemBarcode = "98030205213515";
		final var patronBarcode = "9821734";

		final var hostLmsRenewal = HostLmsRenewal.builder()
			.localItemId(itemId)
			.localPatronId(patronId)
			.localItemBarcode(itemBarcode)
			.localPatronBarcode(patronBarcode)
			.build();

		// With different checkout and item IDs
		sierraItemsAPIFixture.checkoutsForItem(itemId, List.of(
			CheckoutEntry.builder()
				.id("46365756")
				.item("4636566")
				.patron(patronId)
				.build(),
			CheckoutEntry.builder()
				.id("2726588")
				.item("87367573")
				.patron(patronId)
				.build()
		));

		// Act
		final var client = hostLmsFixture.createClient(CIRCULATING_HOST_LMS_CODE);

		final var problem = assertThrows(ThrowableProblem.class,
			() -> singleValueFrom(client.renew(hostLmsRenewal)));

		// Assert
		assertThat(problem, allOf(
			hasProperty("title", is("Checkout ID not found for renewal")),
			hasProperty("detail", is("Multiple checkouts matching local patron id found"))
		));
	}

	@Test
	void shouldReturnProblemWhenGetCheckoutsFails() {
		// Arrange
		final var itemId = "10942942";
		final var patronId = "1182843";
		final var itemBarcode = "98030205213515";
		final var patronBarcode = "9821734";

		final var hostLmsRenewal = HostLmsRenewal.builder()
			.localItemId(itemId)
			.localPatronId(patronId)
			.localItemBarcode(itemBarcode)
			.localPatronBarcode(patronBarcode)
			.build();

		sierraItemsAPIFixture.checkoutsForItemWithNoRecordsFound(itemId);

		// Act
		final var client = hostLmsFixture.createClient(CIRCULATING_HOST_LMS_CODE);

		final var problem = assertThrows(ThrowableProblem.class,
			() -> singleValueFrom(client.renew(hostLmsRenewal)));

		// Assert
		assertThat(problem, allOf(
			hasMessageForRequest("GET", toSierraApiPath("/items/%s/checkouts".formatted(itemId))),
			hasResponseStatusCode(404),
			hasJsonResponseBodyProperty("code", 107),
			hasJsonResponseBodyProperty("httpStatus", 404),
			hasJsonResponseBodyProperty("name", "Record not found"),
			hasJsonResponseBodyProperty("specificCode", 0),
			hasRequestMethod("GET"),
			hasRequestUrl(toSierraUrl("/items/%s/checkouts".formatted(itemId))),
			hasHttpVersion("HTTP_1_1")
		));
	}

	@Test
	void shouldReturnProblemWhenPostRenewalFails() {
		// Arrange
		final var itemId = "10942942";
		final var patronId = "1182843";
		final var itemBarcode = "98030205213515";
		final var patronBarcode = "9821734";

		final var hostLmsRenewal = HostLmsRenewal.builder()
			.localItemId(itemId)
			.localPatronId(patronId)
			.localItemBarcode(itemBarcode)
			.localPatronBarcode(patronBarcode)
			.build();

		final var checkoutId = "2978569";

		sierraItemsAPIFixture.checkoutsForItem(itemId,
			CheckoutEntry.builder()
				.id(checkoutId)
				.patron(patronId)
				.build());

		sierraPatronsAPIFixture.mockRenewalNoRecordsFound(checkoutId);

		// Act
		final var client = hostLmsFixture.createClient(CIRCULATING_HOST_LMS_CODE);

		final var problem = assertThrows(ThrowableProblem.class,
			() -> singleValueFrom(client.renew(hostLmsRenewal)));

		// Assert
		assertThat(problem, allOf(
			hasMessageForRequest("POST", toSierraApiPath("/patrons/checkouts/%s/renewal".formatted(checkoutId))),
			hasResponseStatusCode(404),
			hasJsonResponseBodyProperty("code", 107),
			hasJsonResponseBodyProperty("httpStatus", 404),
			hasJsonResponseBodyProperty("name", "Record not found"),
			hasJsonResponseBodyProperty("specificCode", 0),
			hasRequestMethod("POST"),
			hasRequestUrl(toSierraUrl("/patrons/checkouts/%s/renewal".formatted(checkoutId))),
			hasHttpVersion("HTTP_1_1")
		));
	}

	private static String toSierraUrl(String subPath) {
		return BASE_URL + toSierraApiPath(subPath);
	}

	private static String toSierraApiPath(String subPath) {
		return "/iii/sierra-api/v6" + subPath;
	}
}
