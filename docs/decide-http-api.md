
This document specifies the HTTP API of the *decide-host* operant control software for queries to the trial and event database.

-   Editor: Dan Meliza (dan at meliza.org)
-   Version: 1.0
-   State:  draft
-   URL: <http://meliza.org/specifications/decide-http-api/>

## Goals and framework

The `decide-host` process provides an HTTP service for retrieving trial and event logs as well as information about the current state of the system. The server provides a number of simple endpoints that return JSON-encoded data.

### Endpoints

The API endpoints are accessed using HTTP GET requests. The IP address and port of the server are defined by configuration file. All endpoints are served under the base URI `/api`. Currently defined endpoints are as follows:

- `/controllers` : list of all the controllers
- `/controllers/:addr` : summary for one controller
- `/controllers/:addr/events` : list of events for controller
- `/subjects` : list of all subjects
- `/subjects/active` : list of all active subjects
- `/subjects/inactive` : list of all active subjects
- `/subjects/:uuid` : summary for specific subject
- `/subjects/:uuid/trials` : list of trials for specific subject
- `/subjects/:uuid/stats` : list of summary statistics by hour
- `/subjects/:uuid/stats/today` : summary stats for current day
- `/subjects/:uuid/stats/last-hour` : summary stats for last hour

The URI component `:addr` must refer to the hostname of a controller, and `:uuid` must refer to the hex-encoded UUID of of a subject.

If the endpoint refers to a summary (`/controllers/:addr`/ and `/subjects/:uuid`) and the resource is valid, the response is a single JSON-encoded map. If the resource is not valid, the response status is 404.

If the endpoint refers to more than one entity (noted as lists above), the response is a stream of JSON-encoded maps delimited by CR-LF characters (`\r\n`). List requests may also support query parameters in the URL, as defined below. Times shall be serialized as ISO 8601 strings. If a query fails to match any records, the response status will be 200 but the body will be empty.

#### Query parameters

List results may be filtered, sorted, and paginated using query parameters. Some parameters are specifically defined:

- `comment`: if not specified, records with a non-null value for the `comment`
  field are excluded. If set to `true` or `True`, records with defined comments
  are included. If set to any other value, only records whose comment field
  matches that value are returned.
- `before`: if specified, the value must be a number indicating the number of
  milliseconds since January 1, 1970 (i.e., a long POSIX timestamp). Only
  records with times before this value will be returned.
- `after`: if specified, the value must be a number indicating the number of
  milliseconds since January 1, 1970 (i.e., a long POSIX timestamp). Only
  records with times after this value will be returned.
- `limit`: if specified, the value must be a positive number. Only the requested
  number of records will be returned.
- `skip`: if specified, the value must be a positive number. The returned list
  will skip the number of records indicated.

Other parameters are more general. Parameters that begin with `sort-` will cause the returned results to be sorted by the field specified in the rest of the parameter name, and in the order indicated by the value of the parameter. For example, `sort-time=1` will return results sorted in order of time. The order of sorting operations may not be consistent with the order in the query URL.

All other parameters not matching any given above shall be interpreted as filters that restrict results to records matching the key and value given. For example, `name=hopper` will only return records where the `name` field has the value `hopper`. If multiple parameters with the same key are specified, records that match any of the specified values will be returned.
