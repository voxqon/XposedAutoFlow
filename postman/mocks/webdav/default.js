const http = require("http");

http.createServer(async (req, res) => {
  const path = new URL(req.url, "http://localhost").pathname;
  if (req.method === "POST" && path === "/__reset") {
    await pm.state.set("webdav_nested", { directories: ["/dav/"], files: {} });
    await pm.state.set("webdav_missing_collection", { exists: false });
    res.writeHead(204).end();
    return;
  }
  if (path === "/unauthorized/" || path.startsWith("/unauthorized/")) {
    res.writeHead(401).end();
    return;
  }
  if (path.startsWith("/failure/")) {
    res.writeHead(500, { "Content-Type": "text/html" }).end("<html>Service unavailable</html>");
    return;
  }
  if (path.startsWith("/rate-limited/")) {
    res.writeHead(429, { "Retry-After": "60" }).end();
    return;
  }
  if (path.startsWith("/redirect/")) {
    res.writeHead(302, { Location: "https://example.invalid/credentials-must-not-follow" }).end();
    return;
  }
  if (path.startsWith("/corrupt/")) {
    res.writeHead(200, { "Content-Type": "application/json" }).end('{"format":"other"}');
    return;
  }
  if (path === "/dav" || path.startsWith("/dav/")) {
    // The local simulation gateway removes trailing slashes before dispatching.
    const davPath = ["PROPFIND", "MKCOL"].includes(req.method) && !path.endsWith("/") ?
      `${path}/` : path;
    const expectedAuth = "Basic " +
      Buffer.from("simulation@example.com:simulation-app-password").toString("base64");
    if (req.headers.authorization !== expectedAuth) {
      res.writeHead(401).end();
      return;
    }
    const state = await pm.state.get("webdav_nested") || { directories: ["/dav/"], files: {} };
    const parent = davPath.slice(0, davPath.slice(0, -1).lastIndexOf("/") + 1);
    if (req.method === "PROPFIND") {
      if (req.headers.depth !== "0") { res.writeHead(400).end(); return; }
      if (!state.directories.includes(davPath)) { res.writeHead(404).end(); return; }
      res.writeHead(207, { "Content-Type": "application/xml; charset=utf-8" }).end(
        '<?xml version="1.0"?><multistatus xmlns="DAV:"><response>' +
        "<propstat><prop><resourcetype><collection/></resourcetype></prop>" +
        "<status>HTTP/1.1 200 OK</status></propstat></response></multistatus>"
      );
      return;
    }
    if (req.method === "MKCOL" && davPath.endsWith("/")) {
      if (state.directories.includes(davPath)) { res.writeHead(405).end(); return; }
      if (!state.directories.includes(parent)) { res.writeHead(409).end(); return; }
      state.directories.push(davPath);
      await pm.state.set("webdav_nested", state);
      res.writeHead(201).end();
      return;
    }
    if (davPath.endsWith("/autotasklite-backup.json")) {
      if (req.method === "PUT") {
        if (!state.directories.includes(parent)) { res.writeHead(409).end(); return; }
        const chunks = [];
        for await (const chunk of req) chunks.push(chunk);
        const body = chunks.length ? Buffer.concat(chunks).toString("utf8") :
          (typeof req.body === "string" ? req.body : JSON.stringify(req.body));
        const existed = Object.hasOwn(state.files, davPath);
        state.files[davPath] = body;
        await pm.state.set("webdav_nested", state);
        res.writeHead(existed ? 204 : 201).end();
        return;
      }
      if (req.method === "GET") {
        if (!Object.hasOwn(state.files, davPath)) { res.writeHead(404).end(); return; }
        res.writeHead(200, { "Content-Type": "application/json" }).end(state.files[davPath]);
        return;
      }
    }
    res.writeHead(501).end("DAV operation not modeled");
    return;
  }
  if (req.method === "PROPFIND" && (path === "/backup" || path === "/backup/")) {
    if (req.headers.depth !== "0") {
      res.writeHead(400).end("Depth must be zero");
      return;
    }
    res.writeHead(207, { "Content-Type": "application/xml; charset=utf-8" }).end(
      '<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response>' +
      "<d:href>/backup/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/>" +
      "</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>" +
      "</d:response></d:multistatus>"
    );
    return;
  }
  if ((path === "/missing" || path === "/missing/") && req.method === "MKCOL") {
    await pm.state.set("webdav_missing_collection", { exists: true });
    res.writeHead(201).end();
    return;
  }
  if (path === "/missing/autotasklite-backup.json") {
    const collection = await pm.state.get("webdav_missing_collection");
    if (!collection || !collection.exists) { res.writeHead(404).end(); return; }
    if (req.method === "PUT") {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const body = chunks.length ? Buffer.concat(chunks).toString("utf8") :
        (typeof req.body === "string" ? req.body : JSON.stringify(req.body));
      await pm.state.set("webdav_missing_backup", { content: body });
      res.writeHead(201).end();
      return;
    }
    if (req.method === "GET") {
      const record = await pm.state.get("webdav_missing_backup");
      if (!record) { res.writeHead(404).end(); return; }
      res.writeHead(200, { "Content-Type": "application/json" }).end(record.content);
      return;
    }
  }
  if (path === "/backup/autotasklite-backup.json") {
    if (req.method === "PUT") {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const body = chunks.length ? Buffer.concat(chunks).toString("utf8") :
        (typeof req.body === "string" ? req.body : JSON.stringify(req.body));
      await pm.state.set("webdav_backup", { content: body });
      res.writeHead(201).end();
      return;
    }
    if (req.method === "GET") {
      const record = await pm.state.get("webdav_backup");
      if (!record) { res.writeHead(404).end(); return; }
      res.writeHead(200, { "Content-Type": "application/json" }).end(record.content);
      return;
    }
  }
  res.writeHead(501).end("Path not modeled");
}).listen(process.env.PORT);
