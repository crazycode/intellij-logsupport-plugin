/*
 * Copyright 2010, Juergen Kellerer and other contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.sf.logsupport.util;

import com.intellij.lang.Language;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiElementFilter;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import net.sf.logsupport.config.ApplicationConfiguration;
import net.sf.logsupport.config.LogConfiguration;
import net.sf.logsupport.config.LogFramework;
import net.sf.logsupport.config.LogLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.util.*;

import static net.sf.logsupport.util.ReflectionUtil.invoke;

/**
 * Common set of utility methods to simplify working with PSI.
 *
 * @author Juergen_Kellerer, 2010-04-02
 * @version 1.0
 */
public class LogPsiUtil {

	private static final Logger LOG = Logger.getInstance("#net.sf.logsupport.util.LogPsiUtil");

	private static Reference<Map<String, PsiMethodCallExpression>> logLevelCache =
			new SoftReference<Map<String, PsiMethodCallExpression>>(null);

	private static Language groovy;

	static {
		try {
			for (Language language : Language.getRegisteredLanguages()) {
				if ("groovy".equals(language.getID()))
					groovy = language;
			}
		} catch (Throwable t) {

		}
	}

	/**
	 * Returns the psi element factory used to create literal replacements.
	 *
	 * @param file The file to get the factory for.
	 * @return An instance of element factory for the given project.
	 */
	public static LogPsiElementFactory getFactory(final @NotNull PsiFile file) {
		if (groovy != null && file.getLanguage().is(groovy)) {
			return new LogPsiElementFactory() {
				private final org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElementFactory factory =
						org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElementFactory.getInstance(file.getProject());
				private final PsiParserFacade psiParserFacade = PsiParserFacade.SERVICE.getInstance(file.getProject());

				public PsiField createField(String text, PsiType type, PsiElement context) {
					return null;
				}

				public PsiType createTypeFromText(String text, PsiElement context) {
					return factory.createTypeElement(text).getType();
				}

				public PsiElement createExpressionFromText(String text, PsiElement context) {
					return factory.createExpressionFromText(text);
				}

				public PsiElement createStatementFromText(String text, PsiElement context) {
					return factory.createStatementFromText(text);
				}

				public PsiElement createWhiteSpaceFromText(String text) {
					return psiParserFacade.createWhiteSpaceFromText(text);
				}
			};
		} else {
			return new LogPsiElementFactory() {
				private final PsiElementFactory factory = JavaPsiFacade.getInstance(file.getProject()).getElementFactory();
				private final PsiParserFacade psiParserFacade = PsiParserFacade.SERVICE.getInstance(file.getProject());

				public PsiField createField(String text, PsiType type, PsiElement context) {
					//return factory.createField(text, type);
					return factory.createFieldFromText(type.getCanonicalText() + " " + text + ";", context);
				}

				public PsiType createTypeFromText(String text, PsiElement context) {
					return factory.createTypeFromText(text, context);
				}

				public PsiElement createExpressionFromText(String text, PsiElement context) {
					return factory.createExpressionFromText(text, context);
				}

				public PsiElement createStatementFromText(String text, PsiElement context) {
					return factory.createStatementFromText(text, context);
				}

				public PsiElement createWhiteSpaceFromText(String text) {
					return psiParserFacade.createWhiteSpaceFromText(text);
				}
			};
		}
	}

	/**
	 * Returns the id generator for the given project.
	 *
	 * @param place The place to return an ID generator for.
	 * @return the id generator for the given project.
	 */
	@Nullable
	public static NumericLogIdGenerator getLogIdGenerator(@NotNull PsiElement place) {
		return LogConfiguration.getInstance(place.getContainingFile()).getLogIdGenerator();
	}

	/**
	 * Returns true if the given literal expression contains a log id.
	 *
	 * @param expression The expression to test.
	 * @return true if the given literal expression contains a log id.
	 */
	public static boolean isLogIdPresent(PsiLiteralExpression expression) {
		if (expression != null) {
			String literalText = expression.getText();
			if (literalText.startsWith("\"")) {
				try {
					LogIdGenerator idGenerator = getLogIdGenerator(expression);
					return idGenerator != null && idGenerator.extractId(literalText.substring(1)) != null;
				} catch (PsiInvalidElementAccessException e) {
					return false;
				}
			}
		}
		return false;
	}

	/**
	 * Tries to find the log level for the given logger call expression.
	 *
	 * @param expression The expression to evaluate.
	 * @return The log level of the method call or 'null' if not found.
	 */
	public static LogLevel findLogLevel(PsiMethodCallExpression expression) {
		LogFramework framework = getLogFramework(expression);
		PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();

		if (framework != null && qualifier != null) {
			for (Map.Entry<LogLevel, String> e : framework.getLogMethod().entrySet()) {
				String key = qualifier.getText() + "." + e.getValue().trim() + (e.getValue().contains("(") ? "" : "()");

				Map<String, PsiMethodCallExpression> cache = logLevelCache.get();
				if (cache == null) {
					logLevelCache = new SoftReference<Map<String, PsiMethodCallExpression>>(cache = new HashMap<String, PsiMethodCallExpression>());
				}

				PsiMethodCallExpression referenceExpression = cache.get(key);
				if (referenceExpression == null) {
					LogPsiElementFactory elementFactory = LogPsiUtil.getFactory(expression.getContainingFile());
					PsiElement el = elementFactory.createExpressionFromText(key, expression.getContext());
					if (el instanceof PsiMethodCallExpression)
						cache.put(key, referenceExpression = (PsiMethodCallExpression) el);
				}

				if (referenceExpression != null && isEquivalentTo(expression, referenceExpression))
					return e.getKey();
			}
		}

		return null;
	}

	/**
	 * Returns the configured log framework for the given PsiMethodCallExpression.
	 *
	 * @param expression the expression to lookup the configuration for.
	 * @return the configured log framework for the given PsiMethodCallExpression.
	 */
	@Nullable
	public static LogFramework getLogFramework(PsiMethodCallExpression expression) {
		PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
		if (qualifier == null || !canBeLoggerCall(expression))
			return null;

		final PsiFile file = expression.getContainingFile();
		final PsiType type = findSupportedLoggerType(file, qualifier);
		if (type != null) {
			String loggerClass = type.getCanonicalText();
			LogConfiguration configuration = LogConfiguration.getInstance(file);
			String methodName = expression.getMethodExpression().getReferenceName();
			return configuration.getSupportedFrameworkForLoggerClass(loggerClass, methodName);
		}

		return null;
	}

	/**
	 * Compares 2 method calls and returns true if the full method is equivalent to the partial call.
	 *
	 * @param full	The full call.
	 * @param partial A subset of the full call to compare against.
	 * @return True if full contains the partial.
	 */
	public static boolean isEquivalentTo(PsiMethodCallExpression full, PsiMethodCallExpression partial) {
		if (partial.getMethodExpression().getText().equals(full.getMethodExpression().getText())) {
			Queue<PsiExpression> queue = new ArrayDeque<PsiExpression>(
					Arrays.asList(full.getArgumentList().getExpressions()));
			for (PsiExpression expression : partial.getArgumentList().getExpressions()) {
				PsiExpression e = queue.poll();
				if ("".equals(expression.getText()))
					continue;
				if (e == null || !expression.getText().endsWith(e.getText()))
					return false;
			}

			return true;
		}
		return false;
	}

	/**
	 * Returns all calls to log methods that are supported for processing.
	 *
	 * @param file The file to look for logger calls.
	 * @return all calls to log methods that are supported for processing.
	 */
	@NotNull
	public static List<PsiMethodCallExpression> findSupportedLoggerCalls(PsiFile file) {
		PsiElement[] elements = PsiTreeUtil.collectElements(file, new PsiElementFilter() {
			public boolean isAccepted(PsiElement element) {
				return element instanceof PsiMethodCallExpression &&
						isSupportedLoggerCall((PsiMethodCallExpression) element);
			}
		});

		List<PsiMethodCallExpression> calls = new ArrayList<PsiMethodCallExpression>(elements.length);
		for (PsiElement element : elements)
			calls.add((PsiMethodCallExpression) element);

		return calls;
	}

	/**
	 * Performs a weak name based comparison to decide whether the call expression is possible a log call.
	 *
	 * @param callExpression the call expression to validate.
	 * @return true if the call expression can be a logger call.
	 */
	public static boolean canBeLoggerCall(@NotNull PsiMethodCallExpression callExpression) {
		boolean canBeLogMethod = true;

		// Apply a string filter to check only those method calls that are relevant.
		// This method is called for every method call expression in a file and should be quick.
		final PsiReferenceExpression ref = callExpression.getMethodExpression();
		final PsiElement lastChild = ref.getLastChild();
		final String logMethodName = lastChild == null ? null : lastChild.getText();
		if (logMethodName != null) {
			List<LogFramework> frameworks = ApplicationConfiguration.getInstance().getFrameworks();
			if (!frameworks.isEmpty()) {
				canBeLogMethod = false;

				search:
				for (LogFramework framework : frameworks) {
					for (String methodFragment : framework.getLogMethod().values()) {
						if (canBeLogMethod = methodFragment.contains(logMethodName))
							break search;
					}
				}
			}
		}

		return canBeLogMethod;
	}

	/**
	 * Returns true if the given method call expression represents a supported call to a log method.
	 *
	 * @param callExpression the call expression to test.
	 * @return	'true' if the call expression can be supported.
	 */
	public static boolean isSupportedLoggerCall(PsiMethodCallExpression callExpression) {
		final PsiReferenceExpression ref = callExpression.getMethodExpression();
		if (ref.isQualified() && canBeLoggerCall(callExpression)) {
			final PsiExpression qualifierExpression = ref.getQualifierExpression();
			if (qualifierExpression != null)
				return findSupportedLoggerType(qualifierExpression.getContainingFile(), qualifierExpression) != null;
		}

		return false;
	}

	/**
	 * Returns true if the given logger type is a supported logger.
	 *
	 * @param file	   The file instance containing the logger.
	 * @param loggerType The type of the logger that received the logger call.
	 * @return	'true' if the logger class can be supported.
	 */
	public static boolean isSupportedLoggerCall(@NotNull PsiFile file, PsiType loggerType) {
		return findSupportedLoggerType(file, loggerType) != null;
	}

	/**
	 * Returns the target type of a supported logger class.
	 *
	 * @param file				The file instance containing the logger.
	 * @param qualifierExpression The qualifierExpression that of a log method call expression.
	 * @return the target type of the logger or 'null' if the given type is not supported.
	 */
	@Nullable
	public static PsiType findSupportedLoggerType(@NotNull PsiFile file, @Nullable PsiExpression qualifierExpression) {
		if (qualifierExpression != null) {
			PsiType type = qualifierExpression.getType();

			// Seconds chance, try to resolve the type using a reference.
			if (type == null && qualifierExpression instanceof PsiReferenceExpression) {
				PsiElement resolved = ((PsiReferenceExpression) qualifierExpression).resolve();
				if (resolved instanceof PsiClass) {
					type = LogPsiUtil.getFactory(file).createTypeFromText(((PsiClass) resolved).getQualifiedName(), resolved);
				}
			}

			return findSupportedLoggerType(file, type);
		}
		return null;
	}

	/**
	 * Returns the target type of a supported logger class.
	 *
	 * @param file	   The file instance containing the logger.
	 * @param loggerType The type of the logger that received the logger call.
	 * @return the target type of the logger or 'null' if the given type is not supported.
	 */
	@Nullable
	public static PsiType findSupportedLoggerType(@NotNull PsiFile file, @Nullable PsiType loggerType) {
		if (loggerType != null) {
			List<PsiType> types = Arrays.asList(loggerType);
			while (!types.isEmpty()) {
				for (PsiType type : types) {
					String loggerClass = type.getCanonicalText();
					if (isSupportedLoggerClass(file, loggerClass))
						return type;
				}

				List<PsiType> superTypes = new ArrayList<PsiType>(types.size() * 2);
				for (PsiType type : types) {
					for (PsiType st : type.getSuperTypes())
						superTypes.add(st);
				}

				types = superTypes;
			}
		}

		return null;
	}

	/**
	 * Returns true if the given logger classname is a supported logger.
	 *
	 * @param file	  The file instance containing the logger.
	 * @param className The classname of the logger that received the logger call.
	 * @return	'true' if the logger class can be supported.
	 */
	public static boolean isSupportedLoggerClass(@NotNull PsiFile file, String className) {
		return LogConfiguration.getInstance(file).isSupportedLoggerClass(className);
	}

	/**
	 * Finds and returns a supported method call expression under the current caret.
	 *
	 * @param editor The editor to retrieve the caret position from.
	 * @param file   The underlying parsed file.
	 * @return The supported expression instance or 'null' if not found.
	 */
	@Nullable
	public static PsiMethodCallExpression findSupportedMethodCallExpression(Editor editor, PsiFile file) {
		PsiMethodCallExpression callExpression = findMethodCallExpressionAtCaret(editor, file);
		return callExpression == null || !isSupportedLoggerCall(callExpression) ? null : callExpression;
	}

	/**
	 * Finds and returns a method call expression under the current caret.
	 *
	 * @param editor The editor to retrieve the caret position from.
	 * @param file   The underlying parsed file.
	 * @return The call expression or 'null' if the caret is at a call expression.
	 */
	@Nullable
	public static PsiMethodCallExpression findMethodCallExpressionAtCaret(Editor editor, PsiFile file) {
		final PsiElement psiUnderCaret = PsiUtil.getElementAtOffset(file, editor.getCaretModel().getOffset());
		return findElement(iterateParents(psiUnderCaret),
				PsiMethodCallExpression.class,
				PsiExpressionList.class, PsiBinaryExpression.class,
				PsiReferenceExpression.class, PsiIdentifier.class,
				PsiVariable.class, PsiLiteralExpression.class, PsiJavaToken.class);
	}

	/**
	 * Finds and returns a supported literal expression under the current caret.
	 *
	 * @param editor The editor to retrieve the caret position from.
	 * @param file   The underlying parsed file.
	 * @return The supported expression instance or 'null' if not found.
	 */
	public static PsiLiteralExpression findSupportedLiteralExpression(Editor editor, PsiFile file) {
		PsiMethodCallExpression callExpression = findSupportedMethodCallExpression(editor, file);
		if (callExpression != null)
			return findSupportedLiteralExpression(callExpression.getArgumentList());
		return null;
	}

	@SuppressWarnings("unchecked")
	private static final Class<? extends PsiElement>[] ignoredElementsInParamList = new Class[]{
			PsiJavaToken.class, PsiWhiteSpace.class, PsiAnnotation.class, PsiComment.class,
			PsiReferenceExpression.class};

	@SuppressWarnings("unchecked")
	private static final Class<? extends PsiElement>[] ignoredElementsInBinaryExpression = new Class[]{
			PsiJavaToken.class, PsiWhiteSpace.class, PsiAnnotation.class, PsiComment.class};

	/**
	 * Finds and returns a supported literal expression in the given expression list.
	 *
	 * @param expressionList The expression list containing the literal expression.
	 * @return The supported expression instance or 'null' if not found.
	 */
	@SuppressWarnings("unchecked")
	public static PsiLiteralExpression findSupportedLiteralExpression(PsiExpressionList expressionList) {
		if (expressionList == null)
			return null;

		PsiElement firstChild = expressionList.getFirstChild();
		Class<? extends PsiElement>[] elementIgnoreList = ignoredElementsInParamList;

		PsiLiteralExpression firstExpression = null;

		while (firstChild != null) {
			firstExpression = findElement(iterateSiblings(firstChild, true), PsiLiteralExpression.class, elementIgnoreList);

			// If the literal expression is not inside the expression list, it may be contained
			// inside a binary expression, if so, we retry.
			if (firstExpression == null) {
				PsiBinaryExpression binaryExpression = findElement(iterateSiblings(firstChild, true),
						PsiBinaryExpression.class, elementIgnoreList);
				if (binaryExpression != null) {
					firstChild = binaryExpression.getFirstChild();
					elementIgnoreList = ignoredElementsInBinaryExpression;
				} else
					firstChild = null;
			} else
				firstChild = null;
		}

		if (firstExpression != null && firstExpression.isValid() && firstExpression.getText().startsWith("\""))
			return firstExpression;
		return null;
	}

	/**
	 * Finds an specific element type inside a given iteration.
	 *
	 * @param elements			The elements to iterate.
	 * @param expectedType		The expected type to look for.
	 * @param ignoredElementTypes A set of types that may be skipped in the Iterable.
	 * @param <E>                 The type of the PsiElement to return.
	 * @return The first occurrence of the expected type or 'null' if not found.
	 */
	@SuppressWarnings("unchecked")
	public static <E extends PsiElement> E findElement(Iterable<PsiElement> elements,
													   Class<E> expectedType,
													   Class<? extends PsiElement>... ignoredElementTypes) {
		mainLoop:
		for (PsiElement e : elements) {
			Class<? extends PsiElement> elementClass = e.getClass();

			if (expectedType.isAssignableFrom(elementClass))
				return (E) e;

			for (Class<? extends PsiElement> ignoredElement : ignoredElementTypes)
				if (ignoredElement != null && ignoredElement.isAssignableFrom(elementClass))
					continue mainLoop;

			break;
		}

		return null;
	}

	/**
	 * Resolves the variable initializer behind the given variable reference expression.
	 *
	 * @param referenceExpression the reference expression to resolve.
	 * @return the variable initializer of the variable behind the resolved reference.
	 */
	@Nullable
	public static PsiExpression resolveVariableInitializer(PsiReferenceExpression referenceExpression) {
		PsiReference reference = referenceExpression.getReference();
		PsiElement variable = reference == null ? null : reference.resolve();
		if (variable instanceof PsiVariable)
			return resolveVariableInitializer((PsiVariable) variable);
		return null;
	}

	/**
	 * Resolves the variable initializer behind the given variable reference expression.
	 *
	 * @param variable the reference expression to resolve.
	 * @return the variable initializer of the variable behind the resolved reference.
	 */
	@Nullable
	public static PsiExpression resolveVariableInitializer(PsiVariable variable) {
		if (variable != null) {
			PsiExpression initializer = variable.getInitializer();
			if (initializer instanceof PsiReferenceExpression)
				return resolveVariableInitializer((PsiReferenceExpression) initializer);
			else
				return initializer;
		}
		return null;
	}

	/**
	 * Creates an iterable iterating all parents of the given element.
	 *
	 * @param element The element to use for iterating the parent tree.
	 * @return An Iterable starting from the given element.
	 */
	public static Iterable<PsiElement> iterateParents(final PsiElement element) {
		return new Iterable<PsiElement>() {
			public Iterator<PsiElement> iterator() {
				return new Iterator<PsiElement>() {

					PsiElement el = element;

					public boolean hasNext() {
						return el != null;
					}

					public PsiElement next() {
						try {
							return el;
						} finally {
							el = el.getParent();
						}
					}

					public void remove() {
						throw new UnsupportedOperationException();
					}
				};
			}
		};
	}

	/**
	 * Creates an iterable iterating all siblings of the given element.
	 *
	 * @param element The element to use for iterating the siblings.
	 * @param forward Specifies the direction to iterate (uses next sibling when set to true).
	 * @return An Iterable starting from the given element.
	 */
	public static Iterable<PsiElement> iterateSiblings(final PsiElement element, final boolean forward) {
		return new Iterable<PsiElement>() {
			public Iterator<PsiElement> iterator() {
				return new Iterator<PsiElement>() {

					PsiElement el = element;

					public boolean hasNext() {
						return el != null;
					}

					public PsiElement next() {
						try {
							return el;
						} finally {
							el = forward ? el.getNextSibling() : el.getPrevSibling();
						}
					}

					public void remove() {
						throw new UnsupportedOperationException();
					}
				};
			}
		};
	}

	@Nullable
	public static Object computeConstantExpression(PsiExpression expression) {
		return computeConstantExpression(expression, false);
	}

	@Nullable
	public static Object computeConstantExpression(PsiExpression expression, boolean throwExceptionOnOverflow) {
		final JavaPsiFacade facade = JavaPsiFacade.getInstance(expression.getProject());
		final PsiConstantEvaluationHelper helper = facade.getConstantEvaluationHelper();

		//
		// Fixing compatibility issues between interfaces in IDEA 8,9,10,... by using reflection to call.
		try {
			try {
				return invoke(helper, "computeConstantExpression", expression, throwExceptionOnOverflow);
			} catch (NoSuchMethodError e) {
				if (throwExceptionOnOverflow)
					throw e;
				return invoke(helper, "computeConstantExpression", expression);
			}
		} catch (Exception e) {
			LOG.warn("Failed to invoke computeConstantExpression() via reflection, " +
					"using unsafe direct call (may break in some IDEA versions).");
			return null;
		}
	}


	private LogPsiUtil() {
	}
}
