# Third-Party Software Licenses

OsWL uses the following third-party libraries. This document lists each library, its license, and the full license text or a reference to where it can be found.

---

## Table of Contents

| Library | License |
|---------|---------|
| [Spring Boot / Spring Framework](#spring-boot--spring-framework) | Apache 2.0 |
| [Spring Security](#spring-security) | Apache 2.0 |
| [Spring Data JPA](#spring-data-jpa) | Apache 2.0 |
| [Hibernate ORM](#hibernate-orm) | LGPL 2.1 |
| [Thymeleaf](#thymeleaf) | Apache 2.0 |
| [thymeleaf-extras-springsecurity6](#thymeleaf-extras-springsecurity6) | Apache 2.0 |
| [springdoc-openapi](#springdoc-openapi) | Apache 2.0 |
| [Jackson (Databind / Core / Annotations)](#jackson) | Apache 2.0 |
| [Logback Classic](#logback-classic) | EPL 1.0 / LGPL 2.1 |
| [SLF4J API](#slf4j-api) | MIT |
| [GreenMail](#greenmail) | Apache 2.0 |
| [H2 Database](#h2-database) | EPL 2.0 / MPL 2.0 |
| [PostgreSQL JDBC Driver](#postgresql-jdbc-driver) | BSD 2-Clause |
| [Project Lombok](#project-lombok) | MIT |
| [Alpine.js](#alpinejs) | MIT |
| [@alpinejs/collapse](#alpinjscollapse) | MIT |
| [Chart.js](#chartjs) | MIT |
| [htmx](#htmx) | BSD Zero-Clause (0BSD) |
| [Tailwind CSS](#tailwind-css) | MIT |

---

## Backend Dependencies

### Spring Boot / Spring Framework

- **Version:** Managed by Spring Boot 4.0.5
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

- **Version:** Managed by Spring Boot 4.0.5
- **Website:** https://spring.io/projects/spring-security
- **License:** Apache License, Version 2.0

```
Copyright 2002-2024 the original author or authors.

Licensed under the Apache License, Version 2.0 (the "License").
```

---

### Spring Data JPA

- **Version:** Managed by Spring Boot 4.0.5
- **Website:** https://spring.io/projects/spring-data-jpa
- **License:** Apache License, Version 2.0

```
Copyright 2011-2024 the original author or authors.

Licensed under the Apache License, Version 2.0 (the "License").
```

---

### Hibernate ORM

- **Version:** Managed by Spring Boot 4.0.5
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

- **Version:** Managed by Spring Boot 4.0.5
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

### Jackson

- **Version:** Managed by Spring Boot 4.0.5
- **Website:** https://github.com/FasterXML/jackson
- **License:** Apache License, Version 2.0

```
Copyright 2007-2024 FasterXML, LLC.

Licensed under the Apache License, Version 2.0 (the "License").
```

---

### Logback Classic

- **Version:** Managed by Spring Boot 4.0.5
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

- **Version:** Managed by Spring Boot 4.0.5
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

- **Version:** 2.1.3
- **Website:** https://greenmail-mail-test.github.io/greenmail/
- **License:** Apache License, Version 2.0

```
Copyright 2006-2024 GreenMail Contributors.

Licensed under the Apache License, Version 2.0 (the "License").
```

---

### H2 Database

- **Version:** Managed by Spring Boot 4.0.5
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

- **Version:** Managed by Spring Boot 4.0.5
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

- **Version:** Managed by Spring Boot 4.0.5
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

## Frontend Dependencies (CDN)

These libraries are loaded at runtime from public CDNs and are not bundled inside the JAR artifact.

### Alpine.js

- **Version:** 3.x
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

- **Version:** 3.x
- **Website:** https://alpinejs.dev/plugins/collapse
- **License:** MIT License

```
Copyright 2019-2024 Caleb Porzio and contributors.
(Same MIT terms as Alpine.js above.)
```

---

### Chart.js

- **Version:** 4.4.1
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

- **Version:** 1.9.10
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

- **Version:** 3.4.17 (Standalone CLI binary, not in runtime JAR)
- **Website:** https://tailwindcss.com/
- **License:** MIT License

> Tailwind CSS is used only at build time to generate `oswl-app/src/main/resources/static/css/tailwind.css`. The CLI binary is not shipped with OsWL. Only the generated CSS file is included.

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

## Apache License 2.0 — Full Text

The following libraries are licensed under the Apache License, Version 2.0:
Spring Boot, Spring Framework, Spring Security, Spring Data JPA, Thymeleaf, thymeleaf-extras-springsecurity6, springdoc-openapi, Jackson, GreenMail.

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
