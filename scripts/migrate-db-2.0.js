// migrates 1.0 database to 2.0. In events and trials, the addr key is split
// into addr and name. In trials, the subject field is converted to a binary
// UUID if possible. Note that the binary representation of a UUID in Java (and
// therefore clojure) is different from mongodb's representation, so the
// function below is used instead of the builtin one.

function JUUID(uuid) {
    var hex = uuid.replace(/[{}-]/g, ""); // remove extra characters
    // this will throw an error if the string is not a valid uuid
    var uu = UUID(hex);
    var msb = hex.substr(0, 16);
    var lsb = hex.substr(16, 16);
    msb = msb.substr(14, 2) + msb.substr(12, 2) + msb.substr(10, 2) + msb.substr(8, 2) + msb.substr(6, 2) + msb.substr(4, 2) + msb.substr(2, 2) + msb.substr(0, 2);
    lsb = lsb.substr(14, 2) + lsb.substr(12, 2) + lsb.substr(10, 2) + lsb.substr(8, 2) + lsb.substr(6, 2) + lsb.substr(4, 2) + lsb.substr(2, 2) + lsb.substr(0, 2);
    hex = msb + lsb;
    var base64 = HexToBase64(hex);
    return new BinData(3, base64);
}

db.events.dropIndexes();
db.events.find().snapshot().forEach(
  function (e) {
      // update document
      try {
          var parts = e.addr.split(".");
          e.addr = parts[0];
          e.name = parts[1];
      }
      catch (x) {
          print("W: unable to split addr for", e._id)
      }
      // save the updated document
      db.events.save(e);
  });

db.trials.dropIndexes();
db.trials.find().snapshot().forEach(
  function (e) {
      // update document
      try {
          var parts = e.addr.split(".");
          e.addr = parts[0];
          e.name = parts[1];
      }
      catch (x) {
          print("W: unable to split addr for", e._id);
      }

      try {
          e.subject = JUUID(e.subject);
      }
      catch (x) {}

      // save the updated document
      db.trials.save(e);
  }
)
