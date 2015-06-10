db.events.find().snapshot().forEach(
  function (e) {
      // update document
      try {
          var parts = e.addr.split(".");
          e.addr = parts[0];
          e.name = parts[1];
      }
      catch (x) {}
      // save the updated document
      db.events.save(e);
  }
)
db.trials.find().snapshot().forEach(
  function (e) {
      // update document
      try {
          var parts = e.addr.split(".");
          e.addr = parts[0];
          e.name = parts[1];
      }
      catch (x) {}

      try {
          e.subject = UUID(e.subject.replace(/-/g, ""))
      }
      catch (x) {}

      // save the updated document
      db.trials.save(e);
  }
)
