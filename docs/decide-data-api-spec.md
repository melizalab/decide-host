
This document specifies the API of the *decide-host* operant control software for queries to the trial and event database.

-   Editor: Dan Meliza (dan at meliza.org)
-   Version: 1.0
-   State:  draft
-   URL: <http://meliza.org/specifications/decide-data-api/>



- /controllers : list of all the controllers
- /subjects : all the subjects (summary); support filter
- /subjects/active : all the active subjects; support filter
- /subjects/uuid : specific subject (summary)
- /subjects/uuid/trials : trials for specific subject; support query
  params to filter
- /subjects/uuid/statistics : summary statistics (by hour?)
