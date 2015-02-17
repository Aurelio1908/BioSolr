package uk.co.flax.biosolr.pdbe.solr;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.queries.function.FunctionValues;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.queries.function.docvalues.DoubleDocValues;
import org.apache.lucene.search.FieldCache;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.search.FunctionQParser;
import org.apache.solr.search.SyntaxError;
import org.apache.solr.search.ValueSourceParser;

/**
 * ValueSourceParser to provide a function for retrieving the value of a field from
 * external process results (for use in sort spec, boost function, etc.)
 */
public class XJoinValueSourceParser extends ValueSourceParser {
	
	// the join id field
	private String joinField;
	
	/**
	 * Initialise - set the join id field.
	 */
	@Override
	@SuppressWarnings("rawtypes")
	public void init(NamedList args) {
		super.init(args);
		
		joinField = (String)args.get(XJoinSearchComponent.INIT_JOIN_FIELD);
	}
	
	/**
	 * Provide a ValueSource for external process results, which are obtained from the
	 * request context (having been placed there by XJoinSearchComponent).
	 */
	@Override
	public ValueSource parse(FunctionQParser fqp) throws SyntaxError {
		XJoinResults results = (XJoinResults)fqp.getReq().getContext().get(XJoinSearchComponent.RESULTS_TAG);
		if (results == null) {
			throw new RuntimeException("No external process results in request context");
		}
		return new ExternalValueSource(results, fqp.parseArg());
	}
	
	/**
	 * ValueSource class for external process results.
	 */
	public class ExternalValueSource extends ValueSource {

		// the external process results (generated by XJoinSearchComponent)
		private XJoinResults results;
		
		// the method on external results objects to use as the value
		private String methodName;

		/**
		 * Create an ExternalValueSource for the given external process results, for
		 * extracting the named property (the method used to extract the property is based
		 * on the argument to the function, so e.g. (foo_bar) => getFooBar())
		 */
		public ExternalValueSource(XJoinResults results, String arg) {
			this.results = results;
			this.methodName = NameConverter.getMethodName(arg);
		}

		@Override
		@SuppressWarnings("rawtypes")
		public FunctionValues getValues(Map context, AtomicReaderContext readerContext) throws IOException {
			final BinaryDocValues joinValues = FieldCache.DEFAULT.getTerms(readerContext.reader(), joinField, true);

			return new DoubleDocValues(this) {

				@Override
				public double doubleVal(int doc) {
					BytesRef joinValue = joinValues.get(doc);
					if (joinValue == null) {
						throw new RuntimeException("No joinValue for doc: " + doc);
					}
					Object result = results.getResult(joinValue.utf8ToString());
					if (result == null) {
						throw new RuntimeException("Unknown result: " + joinValue.utf8ToString());
					}
					try {
						Method method = result.getClass().getMethod(methodName);
						return (Double)method.invoke(result);
					} catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
						throw new RuntimeException(e);
					}
				}
				
			};
		}
		
		@Override
		public String description() {
			return "$description$";
		}

		@Override
		public boolean equals(Object object) {
			if (! (object instanceof ExternalValueSource)) {
				return false;
			}
			return results.equals(((ExternalValueSource)object).results);
		}

		@Override
		public int hashCode() {
			return results.hashCode();
		}
		
	}

}
