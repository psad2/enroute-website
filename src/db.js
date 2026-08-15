const Database = require("better-sqlite3");
const fs = require("fs");
const path = require("path");

const dataDirectory = path.join(__dirname, "..", "data");

if (!fs.existsSync(dataDirectory)) {
  fs.mkdirSync(dataDirectory, {
    recursive: true
  });
}

const db = new Database(
  path.join(dataDirectory, "forum.db")
);

db.pragma("journal_mode = WAL");
db.pragma("foreign_keys = ON");

const schemaPath = path.join(
  __dirname,
  "..",
  "schema.sql"
);

const schema = fs.readFileSync(
  schemaPath,
  "utf8"
);

db.exec(schema);

module.exports = db;