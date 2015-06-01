
This document specifies the API of the *decide-host* operant control software for queries to the trial and event database.

-   Editor: Dan Meliza (dan at meliza.org)
-   Version: 1.0
-   State:  draft
-   URL: <http://meliza.org/specifications/decide-data-api/>



- /controllers : list of all the controllers
- /controllers/:addr : summary for one controller
- /controllers/:addr/events : list of events for controller
- /subjects : list of all subjects (summary)
- /subjects/active : list of all active subjects
- /subjects/:uuid : specific subject (summary)
- /subjects/:uuid/trials : list of trials for specific subject
- /subjects/:uuid/stats : list of summary statistics by hour
- /subjects/:uuid/stats/today : summary stats for current day
- /subjects/:uuid/stats/last-hour : summary stats for last hour

All calls that return lists should support query parameters to filter results.

Times are serialized as ISO strings.
