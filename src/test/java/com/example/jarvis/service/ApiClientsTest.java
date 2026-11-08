package com.example.jarvis.service;

import com.example.jarvis.dto.FlightQuote;
import com.example.jarvis.dto.HotelOffer;
import com.example.jarvis.dto.PlaceInfo;
import com.example.jarvis.dto.RestaurantInfo;
import com.example.jarvis.dto.RouteEstimate;
import com.example.jarvis.model.ActivityType;
import com.example.jarvis.model.CabinClass;
import com.example.jarvis.model.StayType;
import com.example.jarvis.model.TransportMode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

// each third-party client against canned responses shaped like the real apis
class ApiClientsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 12, 1);

    private static AirportService airports() {
        String csv = """
                # test airports
                JFK,L,40.6394,-73.7793,US,New York,John F. Kennedy International Airport
                LGA,L,40.7772,-73.8726,US,New York,LaGuardia Airport
                EWR,L,40.6894,-74.1705,US,Newark,Newark Liberty International Airport
                HPN,M,41.0670,-73.7076,US,White Plains,Westchester County Airport
                SEA,L,47.4479,-122.3103,US,Seattle,Seattle-Tacoma International Airport
                """;
        return new AirportService(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void geminiPlannerAndChatUseTheirOwnKeys() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        GeminiService g = new GeminiService(b, "http://gemini", "PLANNER", "m1", "CHAT", "m2");
        server.expect(requestTo("http://gemini/models/m1:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "PLANNER"))
                .andRespond(withSuccess("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://gemini/models/m2:generateContent"))
                .andExpect(header("x-goog-api-key", "CHAT"))
                .andRespond(withSuccess("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Sure!\"}]}}]}", MediaType.APPLICATION_JSON));
        assertThat(g.generatePlan("sys", "plan please")).isEqualTo("{}");
        assertThat(g.chat("sys", List.<String[]>of(new String[]{"user", "hi"}))).isEqualTo("Sure!");
        server.verify();
    }

    @Test
    void orsDrivingRouteFoundNoRouteAndDown() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        OpenRouteService ors = new OpenRouteService(b, "ORSKEY", "http://ors/openrouteservice", "http://ors/pelias/v1");
        server.expect(requestTo("http://ors/openrouteservice/v2/directions/driving-car"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "ORSKEY"))
                .andExpect(jsonPath("$.coordinates[0][0]").value(-74.0))
                .andExpect(jsonPath("$.coordinates[0][1]").value(40.7))
                .andRespond(withSuccess("{\"routes\":[{\"summary\":{\"distance\":1284000.5,\"duration\":46800}}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://ors/openrouteservice/v2/directions/driving-car"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":2009,\"message\":\"Route could not be found\"}}"));
        server.expect(requestTo("http://ors/openrouteservice/v2/directions/driving-car"))
                .andRespond(withServerError());

        OpenRouteService.DriveResult ok = ors.drivingRoute(40.7, -74.0, 41.9, -87.6);
        assertThat(ok.outcome()).isEqualTo(OpenRouteService.Outcome.FOUND);
        assertThat(ok.route().durationMinutes()).isEqualTo(780);
        assertThat(ok.route().distanceKm()).isBetween(1284.0, 1285.0);
        assertThat(ors.drivingRoute(47.6, -122.3, 21.3, -157.8).outcome()).isEqualTo(OpenRouteService.Outcome.NO_ROUTE);
        assertThat(ors.drivingRoute(40.7, -74.0, 41.9, -87.6).outcome()).isEqualTo(OpenRouteService.Outcome.UNAVAILABLE);
        server.verify();

        OpenRouteService noKey = new OpenRouteService(RestClient.builder(), "", "http://ors", "http://ors");
        assertThat(noKey.drivingRoute(1, 1, 2, 2).outcome()).isEqualTo(OpenRouteService.Outcome.UNAVAILABLE);
    }

    @Test
    void orsMatrixCombinesWalkingAndDriving() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        OpenRouteService ors = new OpenRouteService(b, "K", "http://ors", "http://ors/pelias/v1");
        server.expect(requestTo("http://ors/v2/matrix/foot-walking"))
                .andExpect(jsonPath("$.locations[1][0]").value(-122.35))
                .andExpect(jsonPath("$.metrics[0]").value("duration"))
                .andRespond(withSuccess("{\"durations\":[[0,600,5400],[600,0,null],[5400,5000,0]]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://ors/v2/matrix/driving-car"))
                .andRespond(withSuccess("{\"durations\":[[0,180,900],[200,0,1200],[950,1100,0]]}", MediaType.APPLICATION_JSON));
        int[][] m = ors.travelMatrix(List.of(new double[]{47.60, -122.33}, new double[]{47.62, -122.35}, new double[]{47.70, -122.20}));
        // 10 min walk stays a walk
        assertThat(m[0][1]).isEqualTo(10);
        // 90 min walk vs 15 min drive -> ~32 min by transit
        assertThat(m[0][2]).isEqualTo(32);
        // walking unknown, driving 20 min -> transit guess
        assertThat(m[1][2]).isEqualTo(40);
        server.verify();
    }

    @Test
    void orsGeocodeReadsPelias() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        OpenRouteService ors = new OpenRouteService(b, "K", "http://ors", "http://ors/pelias/v1");
        server.expect(requestTo(startsWith("http://ors/pelias/v1/search?text=Kelowna")))
                .andExpect(header("Authorization", "K"))
                .andRespond(withSuccess("""
                        {"type":"FeatureCollection","features":[{"geometry":{"type":"Point","coordinates":[-119.49,49.88]},
                         "properties":{"label":"Kelowna, BC, Canada","country_a":"CAN"}}]}""", MediaType.APPLICATION_JSON));
        var p = ors.geocode("Kelowna").orElseThrow();
        assertThat(p.latitude()).isEqualTo(49.88);
        assertThat(p.longitude()).isEqualTo(-119.49);
        assertThat(p.countryCode()).isEqualTo("CA");
        assertThat(p.formattedName()).isEqualTo("Kelowna, BC, Canada");
        server.verify();
        assertThat(OpenRouteService.alpha3ToAlpha2("USA")).isEqualTo("US");
    }

    @Test
    void transitousRoutesAreParsedAndCached() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).ignoreExpectOrder(true).build();
        TransitousService t = new TransitousService(b, "http://transit", "jarvis-test/1.0 (test@example.com)");
        String rail = """
                {"itineraries":[{"duration":13500,"startTime":"2026-12-01T16:05:00Z","endTime":"2026-12-01T19:50:00Z","transfers":0,
                  "legs":[{"mode":"WALK","distance":350,"from":{"lat":47.6,"lon":-122.33},"to":{"lat":47.598,"lon":-122.33}},
                          {"mode":"LONG_DISTANCE","agencyName":"Amtrak","displayName":"Cascades 507","startTime":"2026-12-01T16:10:00Z","endTime":"2026-12-01T19:45:00Z",
                           "from":{"lat":47.598,"lon":-122.33},"to":{"lat":45.529,"lon":-122.677}}]}]}""";
        String bus = """
                {"itineraries":[{"duration":12000,"startTime":"2026-12-01T17:00:00Z","endTime":"2026-12-01T20:20:00Z","transfers":0,
                  "legs":[{"mode":"COACH","agencyName":"FlixBus","routeShortName":"2010","startTime":"2026-12-01T17:00:00Z","endTime":"2026-12-01T20:20:00Z",
                           "from":{"lat":47.59,"lon":-122.32},"to":{"lat":45.52,"lon":-122.68}}]}]}""";
        server.expect(requestTo(containsString("transitModes=RAIL,COACH,BUS,FERRY")))
                .andExpect(header("User-Agent", "jarvis-test/1.0 (test@example.com)"))
                .andRespond(withSuccess(rail, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("transitModes=RAIL&")))
                .andRespond(withSuccess(rail, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("transitModes=COACH,BUS&")))
                .andRespond(withSuccess(bus, MediaType.APPLICATION_JSON));

        List<RouteEstimate> routes = t.routes(47.6, -122.33, 45.52, -122.68, DAY);
        assertThat(routes).hasSize(3);
        RouteEstimate r = routes.get(0);
        assertThat(r.durationMinutes()).isEqualTo(225);
        assertThat(r.vehicleTypes().get(0)).isEqualTo("RAIL");
        assertThat(r.summary()).isEqualTo("Amtrak Cascades 507");
        // utc 16:10 at longitude -122 is roughly 08:10 local
        assertThat(r.departTime()).isEqualTo("2026-12-01T08:10");
        assertThat(TransportService.classifyTransit(routes.get(2))).isEqualTo(TransportMode.BUS);
        // second call is served from memory
        assertThat(t.routes(47.6, -122.33, 45.52, -122.68, DAY)).hasSize(3);
        server.verify();
    }

    @Test
    void transitousFerryIsAFerry() {
        RouteEstimate r = TransitousService.parseItinerary(JsonSupport.parse("""
                {"duration":10800,"transfers":1,"legs":[
                  {"mode":"BUS","from":{"lat":47.60,"lon":-122.33},"to":{"lat":47.62,"lon":-122.35},"startTime":"2026-12-01T16:00:00Z","endTime":"2026-12-01T16:20:00Z"},
                  {"mode":"FERRY","agencyName":"Clipper","from":{"lat":47.62,"lon":-122.35},"to":{"lat":48.42,"lon":-123.37},"startTime":"2026-12-01T16:30:00Z","endTime":"2026-12-01T19:15:00Z"}]}"""));
        assertThat(TransportService.classifyTransit(r)).isEqualTo(TransportMode.FERRY);
        assertThat(r.transfers()).isEqualTo(1);
        // a walk-only "itinerary" isn't public transport
        assertThat(TransitousService.parseItinerary(JsonSupport.parse("{\"duration\":600,\"legs\":[{\"mode\":\"WALK\",\"distance\":800}]}"))).isNull();
    }

    @Test
    void transitousDownReturnsNull() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        TransitousService t = new TransitousService(b, "http://transit", "ua");
        for (int i = 0; i < 3; i++) server.expect(requestTo(startsWith("http://transit/api/v6/plan"))).andRespond(withServerError());
        assertThat(t.routes(1, 1, 2, 2, DAY)).isNull();
        server.verify();
    }

    @Test
    void geoapifyActivitiesAndRestaurants() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        GeoapifyService g = new GeoapifyService(b, "GKEY", "http://geo");
        String museums = """
                {"type":"FeatureCollection","features":[
                  {"properties":{"name":"Museum of Pop Culture","formatted":"325 5th Ave N, Seattle","lat":47.6215,"lon":-122.3481,"categories":["entertainment.museum"]}},
                  {"properties":{"lat":47.6,"lon":-122.3,"categories":["entertainment.museum"]}}]}""";
        server.expect(requestTo(containsString("categories=entertainment.museum&")))
                .andExpect(queryParam("apiKey", "GKEY"))
                .andExpect(queryParam("filter", "circle:-122.33,47.6,25000"))
                .andRespond(withSuccess(museums, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("tourism.sights.castle"))).andRespond(withSuccess("{\"features\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("place_of_worship"))).andRespond(withServerError());
        // MockRestServiceServer needs every expectation registered before the first request goes out
        server.expect(requestTo(containsString("categories=catering.restaurant.pizza&")))
                .andRespond(withSuccess("""
                        {"features":[{"properties":{"name":"Pagliacci","formatted":"Broadway E","lat":47.62,"lon":-122.32,
                         "catering":{"cuisine":"pizza"},"website":"https://pagliacci.com"}}]}""", MediaType.APPLICATION_JSON));

        List<PlaceInfo> places = g.activityPlaces(ActivityType.HISTORICAL, 47.6, -122.33, 8);
        assertThat(places).extracting(PlaceInfo::name).containsExactly("Museum of Pop Culture");

        List<RestaurantInfo> pizza = g.restaurants("Seattle", "deep dish pizza", 47.6, -122.33, 4);
        assertThat(pizza).hasSize(1);
        assertThat(pizza.get(0).cuisine()).isEqualTo("pizza");
        assertThat(pizza.get(0).url()).isEqualTo("https://pagliacci.com");
        server.verify();

        assertThat(GeoapifyService.cuisineSlug("Sushi")).isEqualTo("sushi");
        assertThat(GeoapifyService.cuisineSlug("BBQ")).isEqualTo("barbecue");
        assertThat(GeoapifyService.cuisineSlug("steak house")).isEqualTo("steak_house");
        assertThat(GeoapifyService.cuisineSlug("food trucks")).isNull();
    }

    @Test
    void geoapifyUnknownCuisineSearchesByName() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        GeoapifyService g = new GeoapifyService(b, "GKEY", "http://geo");
        server.expect(requestTo(containsString("name=food%20trucks"))).andRespond(withSuccess("{\"features\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("categories=catering.restaurant&")))
                .andRespond(withSuccess("{\"features\":[{\"properties\":{\"name\":\"Local Diner\",\"lat\":1,\"lon\":1}}]}", MediaType.APPLICATION_JSON));
        List<RestaurantInfo> r = g.restaurants("Seattle", "food trucks", 47.6, -122.33, 4);
        assertThat(r).extracting(RestaurantInfo::name).containsExactly("Local Diner");
        assertThat(r.get(0).url()).startsWith("https://www.openstreetmap.org/search?query=");
        server.verify();
    }

    @Test
    void airportsPickTheMetroArea() {
        AirportService a = airports();
        assertThat(a.size()).isEqualTo(5);
        assertThat(a.nearest(40.7128, -74.0060, 3)).extracting(AirportService.Airport::iata).containsExactlyInAnyOrder("EWR", "JFK", "LGA");
        assertThat(a.nearest(47.61, -122.33, 3)).extracting(AirportService.Airport::iata).containsExactly("SEA");
        assertThat(a.nearest(0, 0, 3)).isEmpty();
        // the real bundled file loads too
        assertThat(new AirportService().size()).isGreaterThan(3000);
    }

    private static final String SERP_FLIGHTS = """
            {"search_metadata":{"status":"Success"},
             "best_flights":[{"flights":[{"departure_airport":{"id":"JFK","time":"2026-12-01 7:05"},"arrival_airport":{"id":"SEA","time":"2026-12-01 10:20"},
                 "duration":375,"airline":"Alaska"}],"total_duration":375,"price":612,"type":"One way"}],
             "other_flights":[{"flights":[{"departure_airport":{"id":"LGA","time":"2026-12-01 6:00"},"arrival_airport":{"id":"ORD","time":"2026-12-01 7:40"},"duration":160,"airline":"United"},
                                         {"departure_airport":{"id":"ORD","time":"2026-12-01 9:00"},"arrival_airport":{"id":"SEA","time":"2026-12-01 11:30"},"duration":270,"airline":"United"}],
                 "layovers":[{"duration":80,"id":"ORD"}],"total_duration":510,"price":398}]}""";

    @Test
    void serpApiFlights() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        FlightService f = new FlightService(b, airports(), "SERP", "http://serp/search.json", "", "http://sapi", "", "http://duffel", "USD");
        server.expect(requestTo(startsWith("http://serp/search.json?engine=google_flights")))
                .andExpect(queryParam("type", "2"))
                .andExpect(queryParam("travel_class", "3"))
                .andExpect(queryParam("adults", "2"))
                .andExpect(queryParam("children", "1"))
                .andExpect(queryParam("api_key", "SERP"))
                .andExpect(queryParam("arrival_id", "SEA"))
                .andRespond(withSuccess(SERP_FLIGHTS, MediaType.APPLICATION_JSON));
        List<FlightQuote> q = f.search(40.7128, -74.0060, 47.61, -122.33, DAY, 2, 1, CabinClass.BUSINESS);
        assertThat(q).hasSize(2);
        assertThat(q.get(0).carrier()).isEqualTo("Alaska (JFK→SEA)");
        assertThat(q.get(0).departTime()).isEqualTo("2026-12-01T07:05");
        assertThat(q.get(1).stops()).isEqualTo(1);
        assertThat(q.get(1).durationMinutes()).isEqualTo(510);
        assertThat(q.get(1).totalPrice()).isEqualByComparingTo("398");
        // cached: no second request
        assertThat(f.search(40.7128, -74.0060, 47.61, -122.33, DAY, 2, 1, CabinClass.BUSINESS)).hasSize(2);
        server.verify();
    }

    @Test
    void flightsFallBackToSearchApiWhenSerpApiFails() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        FlightService f = new FlightService(b, airports(), "SERP", "http://serp/search.json", "SAPI", "http://sapi/search", "", "http://duffel", "USD");
        server.expect(requestTo(startsWith("http://serp/search.json")))
                .andRespond(withSuccess("{\"error\":\"Your account has run out of searches.\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("http://sapi/search?engine=google_flights")))
                .andExpect(header("Authorization", "Bearer SAPI"))
                .andExpect(queryParam("flight_type", "one_way"))
                .andExpect(queryParam("travel_class", "first_class"))
                .andRespond(withSuccess("""
                        {"best_flights":[{"flights":[{"departure_airport":{"id":"JFK","date":"2026-12-01","time":"22:00"},
                          "arrival_airport":{"id":"SEA","date":"2026-12-02","time":"01:15"},"duration":375,"airline":"Delta"}],
                          "total_duration":375,"price":2410}]}""", MediaType.APPLICATION_JSON));
        List<FlightQuote> q = f.search(40.7128, -74.0060, 47.61, -122.33, DAY, 1, 0, CabinClass.FIRST);
        assertThat(q).hasSize(1);
        assertThat(q.get(0).departTime()).isEqualTo("2026-12-01T22:00");
        assertThat(q.get(0).totalPrice()).isEqualByComparingTo("2410");
        server.verify();
    }

    @Test
    void flightNoResultsIsNotAFailure() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        FlightService f = new FlightService(b, airports(), "SERP", "http://serp/search.json", "SAPI", "http://sapi/search", "", "http://duffel", "USD");
        server.expect(requestTo(startsWith("http://serp/search.json")))
                .andRespond(withSuccess("{\"error\":\"Google Flights hasn't returned any results for this query.\"}", MediaType.APPLICATION_JSON));
        assertThat(f.search(40.7128, -74.0060, 47.61, -122.33, DAY, 1, 0, CabinClass.ECONOMY)).isEmpty();
        server.verify();
        assertThatThrownBy(() -> FlightService.checked(JsonSupport.parse("{\"error\":\"Invalid API key\"}"), "serpapi"))
                .hasMessageContaining("Invalid API key");
    }

    private static final String DUFFEL_OFFERS = """
            {"data":{"id":"orq_1","live_mode":false,"offers":[
              {"id":"off_2","total_amount":"480.00","total_currency":"GBP","owner":{"name":"Duffel Airways"},
               "slices":[{"duration":"PT9H40M","segments":[
                 {"origin":{"iata_code":"JFK"},"destination":{"iata_code":"ORD"},"departing_at":"2026-12-01T06:10:00","arriving_at":"2026-12-01T07:55:00",
                  "duration":"PT2H45M","marketing_carrier":{"name":"Duffel Airways"}},
                 {"origin":{"iata_code":"ORD"},"destination":{"iata_code":"SEA"},"departing_at":"2026-12-01T10:00:00","arriving_at":"2026-12-01T12:50:00",
                  "duration":"PT4H50M","marketing_carrier":{"name":"Duffel Airways"}}]}]},
              {"id":"off_1","total_amount":"350.10","total_currency":"USD","owner":{"name":"Duffel Airways"},
               "slices":[{"duration":"PT6H15M","segments":[
                 {"origin":{"iata_code":"JFK"},"destination":{"iata_code":"SEA"},"departing_at":"2026-12-01T08:00:00","arriving_at":"2026-12-01T11:15:00",
                  "marketing_carrier":{"name":"Duffel Airways"}}]}]},
              {"id":"off_3","total_amount":"99.00","total_currency":"XYZ","slices":[{"segments":[{"origin":{"iata_code":"JFK"}}]}]}]}}""";

    @Test
    void duffelIsTheLastFlightFallback() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        FlightService f = new FlightService(b, airports(), "SERP", "http://serp/search.json", "SAPI", "http://sapi/search",
                "duffel_test_abc", "http://duffel", "USD");
        server.expect(requestTo(startsWith("http://serp/search.json"))).andRespond(withServerError());
        server.expect(requestTo(startsWith("http://sapi/search"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo("http://duffel/air/offer_requests?return_offers=true&supplier_timeout=15000"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer duffel_test_abc"))
                .andExpect(header("Duffel-Version", "v2"))
                .andExpect(jsonPath("$.data.slices[0].origin").value("LGA"))
                .andExpect(jsonPath("$.data.slices[0].destination").value("SEA"))
                .andExpect(jsonPath("$.data.slices[0].departure_date").value("2026-12-01"))
                .andExpect(jsonPath("$.data.passengers.length()").value(3))
                .andExpect(jsonPath("$.data.passengers[2].age").value(8))
                .andExpect(jsonPath("$.data.cabin_class").value("premium_economy"))
                .andRespond(withSuccess(DUFFEL_OFFERS, MediaType.APPLICATION_JSON));
        assertThat(f.isConfigured()).isTrue();
        assertThat(f.duffelTestMode()).isTrue();
        List<FlightQuote> q = f.search(40.7128, -74.0060, 47.61, -122.33, DAY, 2, 1, CabinClass.PREMIUM_ECONOMY);
        // unknown currency dropped, sorted cheapest first, gbp converted
        assertThat(q).hasSize(2);
        assertThat(q.get(0).totalPrice()).isEqualByComparingTo("350.10");
        assertThat(q.get(0).durationMinutes()).isEqualTo(375);
        assertThat(q.get(0).stops()).isZero();
        assertThat(q.get(0).departTime()).isEqualTo("2026-12-01T08:00");
        assertThat(q.get(1).totalPrice()).isEqualByComparingTo("609.60");
        assertThat(q.get(1).stops()).isEqualTo(1);
        assertThat(q.get(1).carrier()).isEqualTo("Duffel Airways (JFK→SEA)");
        assertThat(q).allMatch(x -> x.source().equals("DUFFEL_TEST"));
        server.verify();
    }

    @Test
    void duffelOnlyWithLiveToken() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        FlightService f = new FlightService(b, airports(), "", "http://serp", "", "http://sapi", "duffel_live_xyz", "http://duffel", "USD");
        server.expect(requestTo(startsWith("http://duffel/air/offer_requests")))
                .andRespond(withSuccess(DUFFEL_OFFERS, MediaType.APPLICATION_JSON));
        List<FlightQuote> q = f.search(40.7128, -74.0060, 47.61, -122.33, DAY, 1, 0, CabinClass.ECONOMY);
        assertThat(q).allMatch(x -> x.source().equals("LIVE"));
        assertThat(FlightService.isoMinutes("P1DT2H30M")).isEqualTo(1590);
        assertThat(FlightService.convert(new java.math.BigDecimal("100"), "EUR", "GBP")).isEqualByComparingTo("85.04");
        server.verify();

        RestClient.Builder b2 = RestClient.builder();
        MockRestServiceServer s2 = MockRestServiceServer.bindTo(b2).build();
        FlightService broken = new FlightService(b2, airports(), "", "http://serp", "", "http://sapi", "duffel_live_xyz", "http://duffel", "USD");
        s2.expect(requestTo(startsWith("http://duffel/air/offer_requests")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"errors\":[{\"message\":\"invalid origin\"}]}"));
        assertThat(broken.search(40.7128, -74.0060, 47.61, -122.33, DAY, 1, 0, CabinClass.ECONOMY)).isEmpty();
        s2.verify();
    }

    @Test
    void serpApiHotelsAndRentalsWithPets() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        HotelService h = new HotelService(b, "SERP", "http://serp/search.json", "", "http://sapi", "USD");
        server.expect(requestTo(startsWith("http://serp/search.json?engine=google_hotels&q=Seattle%20hotels&")))
                .andExpect(queryParam("amenities", "19"))
                .andExpect(queryParam("children_ages", "8"))
                .andRespond(withSuccess("""
                        {"properties":[{"type":"hotel","name":"Hotel Max","gps_coordinates":{"latitude":47.61,"longitude":-122.34},
                          "total_rate":{"extracted_lowest":845},"overall_rating":4.3,"extracted_hotel_class":4,
                          "amenities":["Free Wi-Fi","Pet-friendly"],"property_token":"tok1","link":"https://hotelmax.com"},
                         {"type":"hotel","name":"No coords","total_rate":{"extracted_lowest":100}}]}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("http://serp/search.json?engine=google_hotels")))
                .andExpect(queryParam("vacation_rentals", "true"))
                .andExpect(queryParam("amenities", "24"))
                .andRespond(withSuccess("""
                        {"properties":[{"type":"vacation rental","name":"Loft with view","gps_coordinates":{"latitude":47.62,"longitude":-122.35},
                          "rate_per_night":{"extracted_lowest":150},"overall_rating":4.8,"property_token":"tok2"}]}""", MediaType.APPLICATION_JSON));
        List<HotelOffer> offers = h.search("Seattle, WA", DAY, DAY.plusDays(3), 2, 1, true, StayType.ANY);
        assertThat(offers).hasSize(2);
        assertThat(offers.get(0).type()).isEqualTo(StayType.HOTEL);
        assertThat(offers.get(0).totalPrice()).isEqualByComparingTo("845");
        assertThat(offers.get(0).stars()).isEqualTo(4);
        assertThat(offers.get(1).type()).isEqualTo(StayType.HOME);
        // 150 a night x 3 nights
        assertThat(offers.get(1).totalPrice()).isEqualByComparingTo("450");
        assertThat(offers.get(1).url()).startsWith("https://www.google.com/travel/search?q=");
        assertThat(offers).allMatch(HotelOffer::petFriendly);
        server.verify();
    }

    @Test
    void hotelsFallBackToSearchApi() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        HotelService h = new HotelService(b, "SERP", "http://serp/search.json", "SAPI", "http://sapi/search", "USD");
        server.expect(requestTo(startsWith("http://serp/search.json"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(startsWith("http://sapi/search?engine=google_hotels")))
                .andExpect(queryParam("property_type", "vacation_rental"))
                .andExpect(header("Authorization", "Bearer SAPI"))
                .andRespond(withSuccess("""
                        {"properties":[{"type":"vacation_rental","name":"Garden flat","gps_coordinates":{"latitude":47.6,"longitude":-122.3},
                          "price_per_night":{"extracted_price":120},"total_price":{"extracted_price":380},"rating":4.6,"property_token":"v1"},
                         {"type":"vacation_rental","name":"New listing","gps_coordinates":{"latitude":47.61,"longitude":-122.31},
                          "price_per_night":{"extracted_price":90},"property_token":"v2"}]}""",
                        MediaType.APPLICATION_JSON));
        List<HotelOffer> offers = h.search("Seattle", DAY, DAY.plusDays(3), 2, 0, false, StayType.HOME);
        assertThat(offers).hasSize(2);
        assertThat(offers.get(0).totalPrice()).isEqualByComparingTo("380");
        assertThat(offers.get(0).rating()).isEqualTo(4.6);
        assertThat(offers.get(0).type()).isEqualTo(StayType.HOME);
        // no rating and no stars must not break parsing; total comes from the nightly rate
        assertThat(offers.get(1).rating()).isNull();
        assertThat(offers.get(1).stars()).isNull();
        assertThat(offers.get(1).totalPrice()).isEqualByComparingTo("270");
        server.verify();
    }

    @Test
    void demoOffersRespectPets() {
        HotelService h = new HotelService(RestClient.builder(), "", "http://serp", "", "http://sapi", "USD");
        assertThat(h.isConfigured()).isFalse();
        List<HotelOffer> all = h.demoOffers("Seattle", 47.6, -122.3, DAY, DAY.plusDays(2), 2, 0, false, StayType.ANY);
        List<HotelOffer> pets = h.demoOffers("Seattle", 47.6, -122.3, DAY, DAY.plusDays(2), 2, 0, true, StayType.ANY);
        assertThat(pets.size()).isLessThan(all.size());
        assertThat(pets).allMatch(HotelOffer::petFriendly);
        assertThat(all).allMatch(o -> o.name().endsWith("(demo)"));
    }

    @Test
    void wikivoyageSectionsAreSplit() {
        String extract = "Seattle is a city.\n\n== Understand ==\nRainy.\n== See ==\nSpace Needle.\n=== Museums ===\nMoPOP.\n== Sleep ==\nHotels.";
        Map<String, String> s = WikivoyageService.splitSections(extract, 500);
        assertThat(s).containsKeys("Intro", "Understand", "See").doesNotContainKey("Sleep");
        assertThat(s.get("See")).contains("Space Needle").contains("MoPOP").doesNotContain("===");
    }

    @Test
    void geoServiceFallsBackToBuiltInTable() {
        GeoService geo = new GeoService(RestClient.builder(), new OpenRouteService(RestClient.builder(), "", "http://x", "http://x"),
                "http://127.0.0.1:1/search");
        assertThat(geo.geocode("Seattle").orElseThrow().source()).isEqualTo("BUILT_IN");
        assertThat(geo.knownFerryRoute("Seattle, WA", "Victoria")).isTrue();
    }
}
