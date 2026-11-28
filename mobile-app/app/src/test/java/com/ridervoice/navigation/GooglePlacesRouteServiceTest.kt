package com.ridervoice.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GooglePlacesRouteServiceTest {

    @Test
    fun `parseAutocompleteResponse parses structured suggestions and skips non-OK`() {
        val okJson = """
            {
              "status": "OK",
              "predictions": [
                {
                  "place_id": "ChIJ123",
                  "description": "Pune Railway Station, Agarkar Nagar, Pune, Maharashtra",
                  "structured_formatting": {
                    "main_text": "Pune Railway Station",
                    "secondary_text": "Agarkar Nagar, Pune, Maharashtra"
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = GooglePlacesRouteService.parseAutocompleteResponse(okJson)
        assertEquals(1, parsed.size)
        assertEquals("ChIJ123", parsed[0].placeId)
        assertEquals("Pune Railway Station", parsed[0].primaryText)
        assertEquals("Agarkar Nagar, Pune, Maharashtra", parsed[0].secondaryText)

        assertTrue(
            GooglePlacesRouteService.parseAutocompleteResponse("""{"status":"REQUEST_DENIED"}""").isEmpty()
        )
    }

    @Test
    fun `parsePlaceDetailsResponse extracts coordinates and preserves fallback label`() {
        val json = """
            {
              "status": "OK",
              "result": {
                "name": "Tiger Point",
                "formatted_address": "Tiger Point, Lonavala, Maharashtra",
                "geometry": { "location": { "lat": 18.7052, "lng": 73.3924 } }
              }
            }
        """.trimIndent()

        val resolved = GooglePlacesRouteService.parsePlaceDetailsResponse(json, "Tiger Point, Lonavala")
        assertNotNull(resolved)
        assertEquals(18.7052, resolved!!.lat, 0.0001)
        assertEquals(73.3924, resolved.lng, 0.0001)
        assertEquals("Tiger Point, Lonavala", resolved.name)

        assertNull(GooglePlacesRouteService.parsePlaceDetailsResponse("""{"status":"ZERO_RESULTS"}""", "x"))
    }

    @Test
    fun `parseDirectionsResponse and decodePolyline compute distance eta and path`() {
        val dirJson = """
            {
              "status": "OK",
              "routes": [
                {
                  "overview_polyline": { "points": "_p~iF~ps|U_ulLnnqC_mqNvxq`@" },
                  "legs": [
                    {
                      "distance": { "value": 64200 },
                      "duration": { "value": 4510 }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val directions = GooglePlacesRouteService.parseDirectionsResponse(dirJson)
        assertNotNull(directions)
        assertEquals(64200, directions!!.distanceMeters)
        assertEquals(76, directions.durationMinutes)

        val points = GooglePlacesRouteService.decodePolyline(directions.encodedPolyline)
        assertEquals(3, points.size)
        assertEquals(38.5, points[0].latitude, 0.001)
        assertEquals(-120.2, points[0].longitude, 0.001)
        assertEquals(43.252, points[2].latitude, 0.001)
        assertEquals(-126.453, points[2].longitude, 0.001)
    }

    @Test
    fun `parseOsrmDirectionsResponse parses road geometry and next maneuver step`() {
        val osrmJson = """
            {
              "code": "Ok",
              "routes": [
                {
                  "geometry": "_p~iF~ps|U_ulLnnqC_mqNvxq`@",
                  "distance": 42350.5,
                  "duration": 2910.0,
                  "legs": [
                    {
                      "steps": [
                        {
                          "name": "Mumbai-Pune Expy",
                          "maneuver": { "type": "depart", "modifier": "west" }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val directions = GooglePlacesRouteService.parseOsrmDirectionsResponse(osrmJson)
        assertNotNull(directions)
        assertEquals(42350, directions!!.distanceMeters)
        assertEquals(49, directions.durationMinutes)
        assertEquals("Head west on Mumbai-Pune Expy", directions.nextInstruction)
        assertNull(GooglePlacesRouteService.parseOsrmDirectionsResponse("""{"code":"NoRoute"}"""))
    }

    @Test
    fun `parseNominatimResponse extracts lat lng and display labels`() {
        val json = """
            [
              {
                "place_id": 1001,
                "name": "Lonavala",
                "display_name": "Lonavala, Pune District, Maharashtra, India",
                "lat": "18.7557",
                "lon": "73.4091"
              }
            ]
        """.trimIndent()

        val suggestions = GooglePlacesRouteService.parseNominatimResponse(json)
        assertEquals(1, suggestions.size)
        assertEquals("Lonavala", suggestions[0].primaryText)
        assertEquals(18.7557, suggestions[0].lat!!, 0.0001)
        assertEquals(73.4091, suggestions[0].lng!!, 0.0001)
    }
}
