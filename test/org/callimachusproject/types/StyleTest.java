package org.callimachusproject.types;

import org.callimachusproject.test.TemporaryServerTestCase;
import org.callimachusproject.test.WebResource;
import org.junit.Test;

public class StyleTest extends TemporaryServerTestCase {
	private static final String CSS = "hr {color:sienna;} \n" +
		    "p {margin-left:20px;} \n" +
		    "body {background-color:blue}";

	@Test
	public void testLess() throws Exception {
		WebResource create = getHomeFolder().ref("?create=/callimachus/0.18/types/Style&location=test-style.css");
		WebResource resource = create.create("text/css", CSS.getBytes());
		WebResource less = resource.link("alternate", "text/css");
		less.get("text/css");
	}

}
