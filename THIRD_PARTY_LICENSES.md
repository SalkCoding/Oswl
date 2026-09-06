# Third-Party Software Licenses

OsWL uses the following third-party libraries. This document lists each library, its license, and the full license text or a reference to where it can be found.

---

## Table of Contents

| Library                                                               | License                |
|-----------------------------------------------------------------------|------------------------|
| [Spring Boot / Spring Framework](#spring-boot--spring-framework)      | Apache 2.0             |
| [Spring Security](#spring-security)                                   | Apache 2.0             |
| [Spring Data JPA](#spring-data-jpa)                                   | Apache 2.0             |
| [Hibernate ORM](#hibernate-orm)                                       | LGPL 2.1               |
| [Thymeleaf](#thymeleaf)                                               | Apache 2.0             |
| [thymeleaf-extras-springsecurity6](#thymeleaf-extras-springsecurity6) | Apache 2.0             |
| [springdoc-openapi](#springdoc-openapi)                               | Apache 2.0             |
| [Jackson (Databind / Core / Annotations)](#jackson)                   | Apache 2.0             |
| [Logback Classic](#logback-classic)                                   | EPL 1.0 / LGPL 2.1     |
| [SLF4J API](#slf4j-api)                                               | MIT                    |
| [GreenMail](#greenmail)                                               | Apache 2.0             |
| [H2 Database](#h2-database)                                           | EPL 2.0 / MPL 2.0      |
| [PostgreSQL JDBC Driver](#postgresql-jdbc-driver)                     | BSD 2-Clause           |
| [Project Lombok](#project-lombok)                                     | MIT                    |
| [Caffeine](#caffeine)                                                 | Apache 2.0             |
| [Alpine.js](#alpinejs)                                                | MIT                    |
| [@alpinejs/collapse](#alpinejscollapse)                               | MIT                    |
| [Chart.js](#chartjs)                                                  | MIT                    |
| [htmx](#htmx)                                                         | BSD Zero-Clause (0BSD) |
| [Tailwind CSS](#tailwind-css)                                         | MIT                    |
| [CycloneDX Core (Java)](#cyclonedx-core-java)                         | Apache 2.0             |
| [packageurl-java](#packageurl-java)                                   | MIT                    |
| [Micrometer Prometheus Registry](#micrometer-prometheus-registry)     | Apache 2.0             |
| [Flyway](#flyway)                                                     | Apache 2.0             |
| [Spring Security OAuth2 Client](#spring-security-oauth2-client)       | Apache 2.0             |
| [Spring Session JDBC](#spring-session-jdbc)                           | Apache 2.0             |
| [ShedLock](#shedlock)                                                 | Apache 2.0             |
| [Qwen3.5-2B (GGUF)](#qwen35-2b-gguf) | Apache 2.0 |
| [Gemma 4 E2B (GGUF)](#gemma-4-e2b-gguf) | Apache 2.0 |
| [llama.cpp](#llamacpp) | MIT |
| [OSV (Open Source Vulnerabilities)](#osv-open-source-vulnerabilities) | CC-BY 4.0 / CC0 1.0 (varies) |
| [FIRST.org EPSS](#firstorg-epss-exploit-prediction-scoring-system)    | Free access, attribution requested |
| [CISA KEV](#cisa-kev-known-exploited-vulnerabilities-catalog)         | CC0 1.0                |
| [deps.dev](#depsdev)                                                  | CC-BY 4.0 (generated data) / Apache 2.0 (client repo) |
| [CVSS v4.0 Lookup Table (cvss-v4-calculator)](#cvss-v40-lookup-table-cvss-v4-calculator) | BSD 2-Clause |

---

## Backend Dependencies

### Spring Boot / Spring Framework

- **Version:** Managed by Spring Boot 4.1.0
- **Website:** https://spring.io/projects/spring-boot
- **License:** Apache License, Version 2.0

```
Copyright 2012-2024 the original author or authors.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0
```

---

### Spring Security

- **Version:** Managed by Spring Boot 4.1.0
- **Website:** https://spring.io/projects/spring-security
- **License:** Apache License, Version 2.0

```
Copyright 2002-2024 the original author or authors.

Licensed under the Apache License, Version 2.0 (the "License").
```

---

### Spring Data JPA

- **Version:** Managed by Spring Boot 4.1.0
- **Website:** https://spring.io/projects/spring-data-jpa
- **License:** Apache License, Version 2.0

```
Copyright 2011-2024 the original author or authors.

Licensed under the Apache License, Version 2.0 (the "License").
```

---

### Hibernate ORM

- **Version:** Managed by Spring Boot 4.1.0
- **Website:** https://hibernate.org/orm/
- **License:** GNU Lesser General Public License, Version 2.1 (LGPL-2.1)

> **Note (LGPL):** OsWL uses Hibernate ORM as an unmodified dependency via standard JVM class loading. Under LGPL-2.1 §6, end users retain the ability to substitute a different version of Hibernate by replacing the JAR files shipped with OsWL. No Hibernate source code is modified or redistributed.

Full LGPL-2.1 text: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

```
Copyright 2001-2024 Red Hat, Inc. and Hibernate authors.

This library is free software; you can redistribute it and/or
modify it under the terms of the GNU Lesser General Public License
as published by the Free Software Foundation; version 2.1 of the License.

This library is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
Lesser General Public License for more details.
```

---

### Thymeleaf

- **Version:** Managed by Spring Boot 4.1.0
- **Website:** https://www.thymeleaf.org/
- **License:** Apache License, Version 2.0

```
Copyright 2011-2024 The Thymeleaf Team.

Licensed under the Apache License, Version 2.0 (the "License").
```

---

### thymeleaf-extras-springsecurity6

- **Website:** https://github.com/thymeleaf/thymeleaf-extras-springsecurity
- **License:** Apache License, Version 2.0

```
Copyright 2013-2024 The Thymeleaf Team.

Licensed under the Apache License, Version 2.0 (the "License").
```

---

### springdoc-openapi

- **Version:** 3.0.3
- **Website:** https://springdoc.org/
- **License:** Apache License, Version 2.0

```
Copyright 2019-2024 the original author or authors.

Licensed under the Apache License, Version 2.0 (the "License").
```

---

### CycloneDX Core (Java)

- **Version:** 13.0.0
- **Website:** https://github.com/CycloneDX/cyclonedx-core-java
- **License:** Apache License, Version 2.0
- **Used for:** Generating and validating CycloneDX 1.6 SBOM / VEX documents (v1.0.4).

```
Copyright (c) OWASP Foundation.

Licensed under the Apache License, Version 2.0 (the "License").
```

---

### packageurl-java

- **Version:** Managed by cyclonedx-core-java 13.0.0
- **Website:** https://github.com/package-url/packageurl-java
- **License:** MIT License
- **Used for:** Parsing and building package-url (purl) component coordinates for SBOM export/import (v1.0.4).

```
Copyright (c) The Package URL authors.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, subject to the conditions of the MIT License.
```

---

### Micrometer Prometheus Registry

- **Version:** Managed by Spring Boot 4.1.0
- **Website:** https://micrometer.io/
- **License:** Apache License, Version 2.0
- **Used for:** Exposing application metrics at /actuator/prometheus (v1.0.4).

```
Copyright (c) VMware, Inc. / Broadcom.

Licensed under the Apache License, Version 2.0 (the "License").
```

---

### Flyway

- **Version:** Managed by Spring Boot 4.1.0 (flyway-core, flyway-database-postgresql)
- **Website:** https://flywaydb.org/
- **License:** Apache License, Version 2.0
- **Used for:** Opt-in versioned database schema migrations (v1.0.4).

```
Copyright (c) Red Gate Software Ltd.

Licensed under the Apache License, Version 2.0 (the "License").
```

---

### Spring Security OAuth2 Client

- **Version:** Managed by Spring Boot 4.1.0
- **Website:** https://spring.io/projects/spring-security
- **License:** Apache License, Version 2.0
- **Used for:** Optional OIDC single sign-on (Okta / Entra) login (v1.0.4).

```
Copyright 2002-2024 the original author or authors.

Licensed under the Apache License, Version 2.0 (the "License").
```

---

### Spring Session JDBC

- **Version:** Managed by Spring Boot 4.1.0
- **Website:** https://spring.io/projects/spring-session
- **License:** Apache License, Version 2.0
- **Used for:** Cluster-wide HTTP session storage in PostgreSQL, so a multi-instance deployment behind a load balancer keeps users logged in across instances and survives a single instance restarting.

```
Copyright 2014-2024 the original author or authors.

Licensed under the Apache License, Version 2.0 (the "License").
```

---

### ShedLock

- **Version:** 7.7.0 (shedlock-spring, shedlock-provider-jdbc-template)
- **Website:** https://github.com/lukas-krecan/ShedLock
- **License:** Apache License, Version 2.0
- **Used for:** Cluster-wide lock ensuring each `@Scheduled` job (nightly monitoring, deferral expiry, trash cleanup) runs on exactly one instance even when OsWL is deployed with multiple instances.

```
Copyright 2009-2024 the original author(s)

Licensed under the Apache License, Version 2.0 (the "License").
```

---

### Jackson

- **Version:** Managed by Spring Boot 4.1.0
- **Website:** https://github.com/FasterXML/jackson
- **License:** Apache License, Version 2.0

```
Copyright 2007-2024 FasterXML, LLC.

Licensed under the Apache License, Version 2.0 (the "License").
```

---

### Logback Classic

- **Version:** Managed by Spring Boot 4.1.0
- **Website:** https://logback.qos.ch/
- **License:** Eclipse Public License 1.0 (EPL-1.0) **or** GNU Lesser General Public License 2.1 (LGPL-2.1) (dual-licensed; recipient may choose either)

> OsWL ships Logback Classic as an unmodified dependency. No Logback source is modified or redistributed.

Full EPL-1.0 text: https://www.eclipse.org/legal/epl-v10.html  
Full LGPL-2.1 text: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

```
Copyright 1999-2024 QOS.ch.

This program and the accompanying materials are dual-licensed under
the Eclipse Public License v1.0 and the GNU Lesser General Public
License v2.1. You may choose either license.
```

---

### SLF4J API

- **Version:** Managed by Spring Boot 4.1.0
- **Website:** https://www.slf4j.org/
- **License:** MIT License

```
Copyright 2004-2024 QOS.ch

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the "Software"),
to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included
in all copies or substantial portions of the Software.
```

---

### GreenMail

- **Version:** 2.1.11
- **Website:** https://greenmail-mail-test.github.io/greenmail/
- **License:** Apache License, Version 2.0

```
Copyright 2006-2024 GreenMail Contributors.

Licensed under the Apache License, Version 2.0 (the "License").
```

---

### H2 Database

- **Version:** Managed by Spring Boot 4.1.0
- **Website:** https://www.h2database.com/
- **License:** Eclipse Public License 2.0 (EPL-2.0) **or** Mozilla Public License 2.0 (MPL-2.0) (dual-licensed; recipient may choose either)

> OsWL uses H2 as an embedded file-mode database for the `local` development profile. No H2 source is modified.

Full EPL-2.0 text: https://www.eclipse.org/legal/epl-2.0/  
Full MPL-2.0 text: https://www.mozilla.org/en-US/MPL/2.0/

```
Copyright 2004-2024 H2 Group.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License v. 2.0 are satisfied: Mozilla Public License, v. 2.0.
```

---

### PostgreSQL JDBC Driver

- **Version:** Managed by Spring Boot 4.1.0
- **Website:** https://jdbc.postgresql.org/
- **License:** BSD 2-Clause License

```
Copyright (c) 1997, PostgreSQL Global Development Group
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
```

---

### Project Lombok

- **Version:** Managed by Spring Boot 4.1.0
- **Website:** https://projectlombok.org/
- **License:** MIT License
- **Runtime artifact:** Not included — Lombok is a compile-time annotation processor (`compileOnly`/`annotationProcessor`) and generates no runtime bytecode in the distributed JAR.

```
Copyright (C) 2009-2024 The Project Lombok Authors.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.
```

---

### Caffeine

- **Version:** Managed by Spring Boot 4.1.0
- **Website:** https://github.com/ben-manes/caffeine
- **License:** Apache License, Version 2.0
- **Used for:** High-performance in-memory query cache for read-heavy configuration data (license policy, role templates, settings).

```
Copyright 2015 Ben Manes. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0
```

---

## Vendored Frontend Dependencies

Gradle downloads pinned versions of these libraries into `src/main/resources/static/js/vendor/`. They are packaged in the JAR and served locally from `/js/vendor/`; application pages do not load them from a runtime CDN.

### Alpine.js

- **Version:** 3.15.12
- **Website:** https://alpinejs.dev/
- **License:** MIT License

```
Copyright 2019-2024 Caleb Porzio and contributors.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.
```

---

### @alpinejs/collapse

- **Version:** 3.15.12
- **Website:** https://alpinejs.dev/plugins/collapse
- **License:** MIT License

```
Copyright 2019-2024 Caleb Porzio and contributors.
(Same MIT terms as Alpine.js above.)
```

---

### Chart.js

- **Version:** 4.5.1
- **Website:** https://www.chartjs.org/
- **License:** MIT License

```
Copyright 2014-2024 Chart.js Contributors.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.
```

---

### htmx

- **Version:** 2.0.10
- **Website:** https://htmx.org/
- **License:** BSD Zero-Clause License (0BSD)

> 0BSD is equivalent to a public-domain dedication. There are no attribution or notice requirements.

```
Copyright 2019-2024 Big Sky Software.

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted.
```

---

## Build-Time Dependencies

### Tailwind CSS

- **Version:** 3.4.19 (Standalone CLI binary, not in runtime JAR)
- **Website:** https://tailwindcss.com/
- **License:** MIT License

> Tailwind CSS is used only at build time to generate `src/main/resources/static/css/tailwind.css`. The CLI binary is not shipped with OsWL. Only the generated CSS file is included.

```
Copyright 2023 Tailwind Labs, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.
```

---

## Embedded AI Runtime and Models

Runtime binaries and model weights are optional downloads, excluded from the source repository,
Docker build context and released JAR. The JAR includes this notice and the Apache 2.0 text.
Inference runs locally. Installation from upstream is a separate network operation.

### Qwen3.5-2B (GGUF)

- **Role:** Default CPU model; replaces the previous Qwen3-1.7B automatic download.
- **Publisher:** Alibaba Cloud / Qwen team.
- **Upstream:** https://huggingface.co/Qwen/Qwen3.5-2B
- **License:** Apache License, Version 2.0; full text below.
- **Quantization:** Unsloth AI GGUF Q4_K_M. OsWL does not fine-tune or modify the downloaded bytes.
- **Source revision:** `unsloth/Qwen3.5-2B-GGUF@f6d5376be1edb4d416d56da11e5397a961aca8ae`
- **File:** `model/Qwen/Qwen3.5-2B-Q4_K_M.gguf`, 1280835840 bytes.
- **SHA256:** `aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223`
- **Pinned download:** https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/f6d5376be1edb4d416d56da11e5397a961aca8ae/Qwen3.5-2B-Q4_K_M.gguf

### Gemma 4 E2B (GGUF)

- **Role:** Optional CPU model, installed manually; replaces Gemma 3 1B in the refreshed local installation.
- **Publisher:** Google DeepMind.
- **Upstream:** https://huggingface.co/google/gemma-4-E2B-it
- **License:** Apache License, Version 2.0, as stated in the Gemma 4 model card and https://ai.google.dev/gemma/apache_2 .
  This entry is specifically for Gemma 4, not the distinct terms of earlier Gemma releases.
- **Quantization:** Unsloth AI GGUF Q4_K_M. OsWL does not fine-tune or modify the downloaded bytes.
- **Source revision:** `unsloth/gemma-4-E2B-it-GGUF@0314792d7f1f7e229411f620751375812bb9faf2`
- **File:** `model/Gemma/gemma-4-E2B-it-Q4_K_M.gguf`, 3106738272 bytes.
- **SHA256:** `740185b21d22ceb83a11c3aa62ad5842ef32c70f6096d756bbee85a1e4ec34b8`
- **Pinned download:** https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/0314792d7f1f7e229411f620751375812bb9faf2/gemma-4-E2B-it-Q4_K_M.gguf

Apache 2.0 permits redistribution subject to its conditions, including supplying the license,
retaining applicable copyright/attribution notices and carrying forward any upstream NOTICE
content when provided. Preserve the quantization provenance above. Do not represent these weights
as OsWL-authored or imply endorsement by Qwen, Google or Unsloth.

The old GitHub `models-v1` asset is Qwen3-1.7B and is not the new default.
A new mirror must be a verified byte-identical copy of the relevant pinned file and accompany
the license and attribution notices. This change does not publish new model assets.
Custom/older weights retain their own licenses; these entries do not relicense them.

### llama.cpp

- **Publisher:** The ggml authors.
- **Source:** https://github.com/ggml-org/llama.cpp
- **License:** MIT.
- **Validated local runtime:** b10068, commit `571d0d540`; operators supply an OS/architecture-compatible build in `embedded-ai/llama/`.
- **Distribution:** Not bundled in the repository or JAR. If packaging runtime binaries separately,
  preserve this full license and the licenses/notices of all included libraries (for example,
  an OpenMP runtime); this MIT notice alone does not cover every binary in a vendor archive.

```
MIT License

Copyright (c) 2023-2026 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## External Data Sources (Vulnerability / Threat Intelligence Feeds)

Unlike the libraries and models above, the entries below are **data, not code** — consumed live
by `OsvClient`/`DepsDevClient`/`EpssClient`/`KevCatalogService` when air-gapped mode is off, and
by the `oswl-vdb` builder CLI (`com.salkcoding.oswl.vdb`) when constructing an offline
snapshot bundle for air-gapped instances. None of this data is bundled in the git repository or
build artifacts; it is fetched over HTTPS at scan time or at bundle-build time.

### OSV (Open Source Vulnerabilities)

- **Website:** https://osv.dev/ · bulk dumps: `https://storage.googleapis.com/osv-vulnerabilities/<ecosystem>/all.zip`
- **License:** Varies by upstream advisory source, documented per-ecosystem at
  https://google.github.io/osv.dev/data/. For the ecosystems OsWL supports: **npm, Maven,
  RubyGems, NuGet** entries originate from the **GitHub Advisory Database (CC-BY 4.0)**; **PyPI**
  additionally draws from the PyPI Advisory Database and the Python Software Foundation Database
  (both **CC-BY 4.0**); **Go** from the Go Vulnerability Database (**CC-BY 4.0**); **crates.io**
  from the RustSec Advisory Database (**CC0 1.0**, public domain).
- **Used for:** Live per-component vulnerability lookups (`OsvClient`) and, in `oswl-vdb build`,
  bulk re-indexing of the ecosystem `all.zip` dumps into `osv.jsonl` snapshot entries.
- **Attribution:** CC-BY 4.0 requires attribution to the original source; this notice plus OSV's
  own `id`/`aliases` fields preserved verbatim in every re-indexed entry satisfy that.

### FIRST.org EPSS (Exploit Prediction Scoring System)

- **Website:** https://www.first.org/epss/ · bulk scores: `https://epss.empiricalsecurity.com/epss_scores-current.csv.gz`
- **License:** FIRST.org states EPSS scores are made "freely and openly accessible" via CSV and
  API, with attribution requested where possible (https://www.first.org/epss/faq); this is
  narrower than a formal open-data license — the underlying model/training data are explicitly
  **not** shared per that FAQ, only the published per-CVE scores OsWL consumes.
- **Used for:** Live per-CVE probability-of-exploitation scores (`EpssClient`) and, in
  `oswl-vdb build`, the full bulk CSV.
- **Attribution:** This notice + preserving FIRST.org as the named source satisfies the
  attribution request.

### CISA KEV (Known Exploited Vulnerabilities Catalog)

- **Website:** https://www.cisa.gov/known-exploited-vulnerabilities-catalog
- **License:** **CC0 1.0** (public domain) — a work of the U.S. federal government, mirrored
  under CC0 at https://github.com/cisagov/kev-data.
- **Used for:** Live KEV-listed flagging (`KevCatalogService`) and, in `oswl-vdb build`, the full
  bulk JSON feed.

### deps.dev

- **Website:** https://deps.dev/ · API: https://docs.deps.dev/api/v3/ · source:
  https://github.com/google/deps.dev
- **License:** The deps.dev README states: *"deps.dev generates additional data, including
  resolved dependencies, advisory statistics, associations between entities, etc. This generated
  data is available under a **CC-BY 4.0** license."* This covers the derived fields OsWL consumes
  (`licenses`, `advisoryKeys`, resolved version/dependency data). Advisory content itself
  (GHSA title/CVSS surfaced via `GetAdvisory`) originates from OSV/GHSA and is independently
  CC-BY 4.0 per the OSV entry above. The raw registry fields deps.dev merely aggregates (not
  generates) have no independently stated license and inherit whatever terms the origin registry
  applies. Access to the API itself is governed by the
  [Google APIs Terms of Service](https://developers.google.com/terms), which explicitly permits
  caching: *"Clients are expressly permitted to cache data served by the API."* The deps.dev
  **client repository's own code** (not the data) is Apache 2.0.
- **Used for:** Live per-version license/advisory-key lookups and Scorecard scores
  (`DepsDevClient`) and, in `oswl-vdb build`, targeted `GetVersion`/`GetAdvisory` calls against a
  wanted-list — deps.dev has no bulk dump, so this is the only viable ingestion path.
- **Note:** Given the licensing ambiguity above, treat deps.dev-derived fields (`licenses`,
  `advisoryKeys`, GHSA advisory title/CVSS) the same way the rest of this codebase already does —
  as data used to power OsWL's own analysis output, not redistributed as a standalone dataset.
  wanted-list — deps.dev has no bulk dump, so this is the only viable ingestion path.
- **Attribution:** This notice + the OSV/GHSA attribution above satisfies CC-BY 4.0 for the
  generated and advisory data. deps.dev-derived fields are used to power OsWL's own analysis
  output, not redistributed as a standalone dataset.

### CVSS v4.0 Lookup Table (cvss-v4-calculator)

- **Source:** https://github.com/FIRSTdotorg/cvss-v4-calculator (`cvss_lookup.js`, `max_composed.js`,
  `max_severity.js`) · publisher: FIRST.org, Inc., Red Hat, and contributors
- **License:** BSD 2-Clause
- **Note:** Unlike the other entries in this section, this is not a live-queried feed — the
  270-entry MacroVector→score table and its supporting per-equivalence-class data are vendored
  verbatim as `src/main/resources/cvss/cvss-v4-lookup.json`, converted from the source `.js` object
  literals to JSON with no values changed. `service/cvss/CvssV4Calculator.java` reimplements the
  surrounding scoring algorithm (MacroVector derivation, severity-distance interpolation) in Java
  from the same reference source, since the algorithm itself is not data that can be vendored as a
  file.
- **Used for:** CVSS v4.0 Base/Environmental scoring (`CvssV4Calculator`) when a CVE supplies a
  `CVSS:4.0/...` vector.

```
Copyright (c) 2023 FIRST.ORG, Inc., Red Hat, and contributors

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
```

---

## Apache License 2.0 — Full Text

The following libraries and models are licensed under the Apache License, Version 2.0:
Spring Boot, Spring Framework, Spring Security, Spring Data JPA, Thymeleaf, thymeleaf-extras-springsecurity6, springdoc-openapi, Jackson, GreenMail, Spring Session JDBC, ShedLock, Qwen3.5-2B, Gemma 4 E2B.

```
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship made available under
      the License, as indicated by a copyright notice that is included in
      or attached to the work.

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship.

      "Contribution" shall mean, as submitted to the Licensor for inclusion
      in the Work by the copyright owner or by an individual or Legal Entity
      authorized to submit on behalf of the copyright owner.

      "Contributor" shall mean Licensor and any Legal Entity on behalf of
      whom a Contribution has been received by the Licensor and included
      within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work.

   4. Redistribution. You may reproduce and distribute copies of the Work
      or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You meet
      the following conditions:

      (a) You must give any other recipients of the Work or Derivative
          Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works that
          You distribute, all copyright, patent, trademark, and attribution
          notices from the Source form of the Work, excluding those notices
          that do not pertain to any part of the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file, You must include a
          readable copy of the attribution notices contained within such
          NOTICE file, in at least one of the following places: within a
          NOTICE text distributed as part of the Derivative Works; within
          the Source form or documentation, if provided along with the
          Derivative Works; or, within a display generated by the Derivative
          Works, if and wherever such third-party notices normally appear.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      shall be under the terms and conditions of this License.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor.

   7. Disclaimer of Warranty. Unless required by applicable law or agreed
      to in writing, Licensor provides the Work on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied.

   8. Limitation of Liability. In no event and under no legal theory shall
      any Contributor be liable to You for damages, including any direct,
      indirect, special, incidental, or exemplary damages of any character
      arising as a result of this License or out of the use or inability to
      use the Work.

   9. Accepting Warranty or Additional Liability. While redistributing the
      Work or Derivative Works thereof, You may choose to offer, and charge
      a fee for, acceptance of support, warranty, indemnity, or other
      liability obligations and rights consistent with this License.

   END OF TERMS AND CONDITIONS
```

## Additional rule and browser notification libraries

Versions are resolved in the generated OSS manifest and displayed on `/oss-notices`.

- **RE2/J**: BSD 3-Clause. [Upstream license/project](https://github.com/google/re2j/blob/re2j-1.8/LICENSE).
- **web-push**: MIT. [Upstream license/project](https://github.com/web-push-libs/webpush-java).
- **Bouncy Castle**: MIT. [Upstream license/project](https://www.bouncycastle.org/licence.html).
- **jose4j**: Apache 2.0. [Upstream license/project](https://bitbucket.org/b_c/jose4j).

RE2/J derives from the Go RE2 implementation, copyright 2009 The Go Authors. Bouncy Castle is copyright 2000–2026 The Legion of the Bouncy Castle Inc.; its license is interpreted as MIT by the publisher. The distributed dependency JARs retain their upstream notices.
