// run these commands in the mongo shell to set up indices in the database

use decide

db.events.ensureIndex({addr: 1, name: 1});
db.events.ensureIndex({addr: 1, time: 1});

db.trials.ensureIndex({addr: 1, name: 1});
db.trials.ensureIndex({addr: 1, time: 1});
db.trials.ensureIndex({subject: 1, experiment: 1, trial: 1})

db.subjects.ensureIndex({addr: 1});
db.subjects.ensureIndex({user: 1});
db.subjects.ensureIndex({procedure: 1});

db.controllers.ensureIndex({"zmq-id": 1});
db.controllers.ensureIndex({addr: 1});
