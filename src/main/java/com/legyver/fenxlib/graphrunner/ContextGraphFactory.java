package com.legyver.fenxlib.graphrunner;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class ContextGraphFactory {

	private final VariableExtractionOptions variableExtractionOptions;
	private final VariableTransformationRule variableTransformationRule;

	public ContextGraphFactory(VariableExtractionOptions variableExtractionOptions, VariableTransformationRule variableTransformationRule) {
		this.variableExtractionOptions = variableExtractionOptions;
		this.variableTransformationRule = variableTransformationRule;
	}

	public ContextGraphFactory(VariableExtractionOptions variableExtractionOptions) {
		this(variableExtractionOptions, null);
	}

	public ContextGraphFactory(Pattern tokenizerPattern, int group) {
		this(new VariableExtractionOptions(tokenizerPattern, group));
	}

	/**
	 * Make a directional graph of properties that reference other properties
	 * example:
	 *   major.version=1
	 *   minor.version=0
	 *   patch.number=0
	 *
	 *   build.number=0000
	 *   build.date.day=11
	 *   build.date.month=April
	 *   build.date.year=2020
	 *
	 *   build.date.format=`${build.date.day} ${build.date.month} ${build.date.year}`
	 *   build.version.format=`${major.version}.${minor.version}.${patch.number}.${build.number}`
	 *   build.message.format=`Build ${build.version}, built on ${build.date}`
	  Note to make build.version resolve as the outcome of build.version.format, we need to use specify this in the {@link variableTransformationRule}
	 * @param properties
	 * @return
	 */
	public ContextGraph make(Properties...properties) {
		ContextGraph contextGraph = new ContextGraph();
		if (properties != null) {
			List<ReferenceProperty> propertiesToResolve = new ArrayList<>();
			Stream.of(properties).forEach(p -> addResolvingProperties(p, propertiesToResolve));
			List<DirectionalProperty> additionalProperties = new ArrayList<>();

			List<DirectionalProperty> directionalProperties = link(propertiesToResolve);
			if (variableTransformationRule != null) {
				propertiesToResolve.stream()
						.filter(referenceProperty -> variableTransformationRule.matches(referenceProperty.key))
						.forEach(referenceProperty -> {
							//ex: Since the result of the operation on build.date.format is set as build.date
							// build.date.format must be resolved first before any operation involving build.date can be executed.
							// in this example, transformed would be build.date and referenceProperty.key would be build.date.format
							String transformed = variableTransformationRule.transform(referenceProperty.key);
							//so the dependency is transformed depends on referenceProperty.key
							DirectionalProperty directionalProperty = new DirectionalProperty(transformed);
							directionalProperty.depends.add(referenceProperty.key);
							additionalProperties.add(directionalProperty);
						});
			}
			//doing this to avoid modifying a collection mid-stream and incurring a ConcurrentModificationException
			directionalProperties.addAll(additionalProperties);

			for (DirectionalProperty directionalProperty : directionalProperties) {
				for (String predecessor : directionalProperty.depends) {
					contextGraph.accept(directionalProperty.key, predecessor);
				}
			}
		}
		return contextGraph;
	}

	private List<DirectionalProperty> link(List<ReferenceProperty> propertiesToResolve) {
		List<DirectionalProperty> result = new ArrayList<>();
		for (ReferenceProperty property : propertiesToResolve) {
			DirectionalProperty directionalProperty = new DirectionalProperty(property.key);
			result.add(directionalProperty);
			String propertyValue = property.value;
			Matcher m = variableExtractionOptions.getTokenizerPattern().matcher(propertyValue);
			while (m.find()) {
				String group = m.group(variableExtractionOptions.getGroup());
				directionalProperty.depends.add(group);
			}
		}
		return result;
	}

	private void addResolvingProperties(Properties properties, List<ReferenceProperty> propertiesToResolve) {
		properties.stringPropertyNames().stream()
				.map(s -> new ReferenceProperty(s, properties.getProperty(s)))
				.forEach(referenceProperty -> propertiesToResolve.add(referenceProperty));
	}

	private class ReferenceProperty {
		private final String key;
		private final String value;

		private ReferenceProperty(String key, String value) {
			this.key = key;
			this.value = value;
		}
	}

	private class DirectionalProperty {
		private final String key;
		private final Set<String> depends = new HashSet<>();

		private DirectionalProperty(String key) {
			this.key = key;
		}
	}
}
