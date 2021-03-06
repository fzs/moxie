/*
 * Copyright 2002-2005 The Apache Software Foundation.
 * Copyright 2012 James Moger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.moxie.proxy;

/**
 * @author digulla
 */
public class AllowDeny {
	private final String url;
	private boolean allow;

	public AllowDeny(String url, boolean allow) {
		this.url = url;
		this.allow = allow;
	}

	public boolean matches(String url) {
		return url.startsWith(this.url);
	}

	public boolean isAllowed() {
		return allow;
	}

	public String getURL() {
		return url;
	}
}
