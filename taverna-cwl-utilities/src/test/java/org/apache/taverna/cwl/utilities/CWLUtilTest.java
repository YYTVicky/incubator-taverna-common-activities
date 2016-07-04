package org.apache.taverna.cwl.utilities;

import static org.junit.Assert.*;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.Before;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

public class CWLUtilTest {
	Map cwlFile;
	CWLUtil cwlUtil;
	Map input;

	@Before
	public void setUp() throws Exception {
		Yaml reader = new Yaml();
		Path path = Paths.get("CWLFiles", "customtool1.cwl");
		cwlFile = (Map) reader.load(new FileInputStream(path.toFile()));
		cwlUtil = new CWLUtil(cwlFile);
		input = ((ArrayList<Map>) cwlFile.get("inputs")).get(0);
	}

	@Test
	public void processNameSpaceTest() {
		LinkedHashMap nameSpace = cwlUtil.getNameSpace();

		assertEquals(1, nameSpace.size());
		assertTrue(nameSpace.containsKey("edam"));
		assertEquals("http://edamontology.org/", nameSpace.get("edam"));
	}

	@Test
	public void extractLabelTest() {
		PortDetail detail = new PortDetail();

		cwlUtil.extractLabel(null, detail);
		assertEquals(null, detail.getLabel());

		cwlUtil.extractLabel(input, detail);
		assertEquals("input 1 testing label", detail.getLabel());

	}

	@Test
	public void extractDescriptionTest() {
		PortDetail detail = new PortDetail();

		cwlUtil.extractDescription(null, detail);
		assertEquals(null, detail.getDescription());

		cwlUtil.extractDescription(input, detail);
		assertEquals("this is a short description of input 1", detail.getDescription());

	}

	@Test
	public void figureOutFormatsTest() {
		PortDetail detail = new PortDetail();
		detail.setFormat(new ArrayList<String>());
		cwlUtil.figureOutFormats("edam:1245", detail);
		assertEquals("http://edamontology.org/1245", detail.getFormat().get(0));

		cwlUtil.figureOutFormats("$formatExpression", detail);
		assertEquals("$formatExpression", detail.getFormat().get(1));

		// format that doesn't defined in the name space

		cwlUtil.figureOutFormats("formatkey: not Defined", detail);
		assertEquals("formatkey: not Defined", detail.getFormat().get(2));
	}

	@Test
	public void processTest() {

		HashMap<String, Integer> actual = cwlUtil.process(((ArrayList<Map>) cwlFile.get("inputs")));

		HashMap<String, Integer> expected = new HashMap<>();
		expected.put("input_1", 0);
		expected.put("input_2", 1);
		expected.put("input_3", 0);
		for (Map.Entry<String, Integer> input : expected.entrySet()) {
			assertEquals(input.getValue(), actual.get(input.getKey()));
		}
		
		actual = cwlUtil.process(((ArrayList<Map>) cwlFile.get("outputs")));
		expected = new HashMap<>();
		expected.put("output_1", 0);
		expected.put("output_2", 0);
		for (Map.Entry<String, Integer> input : expected.entrySet()) {
			assertEquals(input.getValue(), actual.get(input.getKey()));
		}
	}
}
