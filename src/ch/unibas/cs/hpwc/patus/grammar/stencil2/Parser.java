package ch.unibas.cs.hpwc.patus.grammar.stencil2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import cetus.hir.ArrayAccess;
import cetus.hir.AssignmentExpression;
import cetus.hir.AssignmentOperator;
import cetus.hir.BinaryExpression;
import cetus.hir.BinaryOperator;
import cetus.hir.DepthFirstIterator;
import cetus.hir.Expression;
import cetus.hir.FloatLiteral;
import cetus.hir.FunctionCall;
import cetus.hir.IntegerLiteral;
import cetus.hir.Literal;
import cetus.hir.NameID;
import cetus.hir.Specifier;
import cetus.hir.UnaryExpression;
import cetus.hir.UnaryOperator;

import ch.unibas.cs.hpwc.patus.codegen.CodeGenerationOptions;
import ch.unibas.cs.hpwc.patus.geometry.Box;
import ch.unibas.cs.hpwc.patus.geometry.Point;
import ch.unibas.cs.hpwc.patus.representation.Index;
import ch.unibas.cs.hpwc.patus.representation.Stencil;
import ch.unibas.cs.hpwc.patus.representation.StencilBundle;
import ch.unibas.cs.hpwc.patus.representation.StencilCalculation;
import ch.unibas.cs.hpwc.patus.representation.StencilNode;
import ch.unibas.cs.hpwc.patus.symbolic.ExpressionData;
import ch.unibas.cs.hpwc.patus.symbolic.Symbolic;
import ch.unibas.cs.hpwc.patus.util.CodeGeneratorUtil;
import ch.unibas.cs.hpwc.patus.util.ExpressionUtil;
import ch.unibas.cs.hpwc.patus.util.IntArray;
import ch.unibas.cs.hpwc.patus.util.StringUtil;




public class Parser {
	public static final int _EOF = 0;
	public static final int _ident = 1;
	public static final int _integer = 2;
	public static final int maxT = 51;

	static final boolean T = true;
	static final boolean x = false;
	static final int minErrDist = 2;
	
	static final org.apache.log4j.Logger LOGGER = org.apache.log4j.Logger.getLogger (Parser.class);
	

	public Token t;    // last recognized token
	public Token la;   // lookahead token
	int errDist = minErrDist;
	
	public Scanner scanner;
	public Errors errors;

	enum EStreamDirection
	{
		/**
		 * A stream from which data is read
		 */
		INPUT,
		
		/**
		 * A stream to which data is written to
		 */
		OUTPUT
	}
	
	/**
	 * Helper class for mapping multidimensional and differently named identifiers
	 * to a normalized set of identifiers (map to vector components of StencilNodes).
	 */
	protected class StreamIndex
	{
		private Specifier m_specType;
		private boolean m_bIsConstant;
		private Box m_boxStreamDimensions;
		private int m_nStartIndex;
		private int[] m_rgDimensions;
		
		/**
		 * Constructs a new stream index.
		 */
		public StreamIndex (String strName, Specifier specType, boolean bIsConstant, Box boxDimensions, List<Integer> listDimensions, EStreamDirection sd)
		{
			m_specType = specType;
			m_bIsConstant = bIsConstant;
			m_boxStreamDimensions = boxDimensions;
			
			// copy the dimensions
			m_rgDimensions = new int[listDimensions.size ()];
			int i = 0;
			for (int nDim : listDimensions)
				m_rgDimensions[i++] = nDim;
			
			// calculate the total number of dimensions
			int nStreamsCount = 1;
			for (int nDim : listDimensions)
				nStreamsCount *= nDim;

			// set the start index and the new number of total streams
			StreamIndex idx = m_mapInputStreams.get (strName);
			if (idx == null)
				idx = m_mapOutputStreams.get (strName);
			if (idx != null)
				m_nStartIndex = idx.m_nStartIndex;
			else
			{
				m_nStartIndex = m_nTotalStreamsCount;
				m_nTotalStreamsCount += nStreamsCount;
			}
		}
		
		/**
		 * Returns the index.
		 */
		public int getLinearIndex (List<Integer> listIndices)
		{
			if (listIndices.size () != m_rgDimensions.length)
			{
				errors.SemErr (la.line, la.col, "Parameter dimension does not agree with its definition (should be " + listIndices.size () + ")");
				return -1;
			}
			
			int nIdx = 0;
			int i = 0;
			for (int nIdxValue : listIndices)
			{
				// check whether the indices are within the defined bounds
				if (nIdxValue < 0 || nIdxValue >= m_rgDimensions[i])
				{
					errors.SemErr (la.line, la.col, "Index out of bounds (should be 0.." + (m_rgDimensions[i] - 1) + ")");
					return -1;
				}
				
				// caluclate index
				nIdx = nIdx * m_rgDimensions[i] + nIdxValue;
				i++;
			}
			
			return nIdx + m_nStartIndex;
		}
		
		/**
		 * Returns the grid type.
		 */
		public Specifier getSpecifier ()
		{
			return m_specType;
		}
		
		/**
		 * Returns <code>true</code> iff the grid is specified to be a constant grid, i.e. does not change in time.
		 */
		public boolean isConstant ()
		{
			return m_bIsConstant;
		}
		
		/**
		 * Returns the box, i.e., the dimensions of the stream in each direction.
		 */
		public Box getStreamDimensions ()
		{
            return m_boxStreamDimensions;
		}
	}

	
	///////////////////////////////////////////////////////////////////////////
	// Member Variables

	/**
	 * The result of the parser
	 */
	private StencilCalculation m_stencil;
	
	/**
	 * The data streams from which data are read
	 */
	private Map<String, StreamIndex> m_mapInputStreams;
	
	/**
	 * The data streams to which data are written
	 */
	private Map<String, StreamIndex> m_mapOutputStreams;
	
	/**
	 * Scalars: Parameters passed to the stencil operation or temporary variables defined
	 * within the stencil operation
	 */
	private Map<String, StencilCalculation.ParamType> m_mapScalars;
	
	/**
	 * Constants: variables that are assigned a constant value within the stencil operation
	 */
	private Map<String, Literal> m_mapConstants;
	
	/**
	 * List of arguments to the stencil operation (in the original order)
	 */
	private List<String> m_listStencilOperationArguments;
	
	/**
	 * A list of size parameters to the stencil definition (contained in the
	 * domain size definition and the optional size parameters to the grids)
	 */
	private List<NameID> m_listSizeParameters;
	
	/**
	 * The current total number of registered streams
	 */
	private int m_nTotalStreamsCount = 0;

	/**
	 * Has <code>t_max</code> been set?
	 */
	private boolean m_bIterateWhileSet = false;
	
	private CodeGenerationOptions m_options;

		
	///////////////////////////////////////////////////////////////////////////
	// Custom Implementation
	
	public void setOptions (CodeGenerationOptions options)
	{
		m_options = options;
	}
	
	public boolean hasErrors ()
	{
		return errors.count > 0;
	}
	
	/**
	 * Returns the stencil calculation object.
	 */
	public StencilCalculation getStencilCalculation ()
	{
		return m_stencil;
	}
	
	private Literal createLiteral (double fValue, boolean bIsIntegerLiteral)
	{
		return bIsIntegerLiteral ? new IntegerLiteral ((long) fValue) : new FloatLiteral (fValue);
	}
	
	/**
	 * Create a balanced sum expression.
	 */
	private ExpressionData sum (List<ExpressionData> listSummands, boolean bIsInteger)
	{
		List<ExpressionData> listSummandsSimplified = new ArrayList<> (listSummands.size ());
		double fSum = 0;
		for (ExpressionData expr : listSummands)
		{
			if (ExpressionUtil.isNumberLiteral (expr.getExpression ()))
				fSum += ExpressionUtil.getFloatValue (expr.getExpression ());
			else
				listSummandsSimplified.add (expr);
		}
		
		ExpressionData exprExplicitSum = new ExpressionData (createLiteral (fSum, bIsInteger), 0, Symbolic.EExpressionType.EXPRESSION);
		if (listSummandsSimplified.size () == 0)
			return exprExplicitSum;
		
		if (fSum != 0)
			listSummandsSimplified.add (exprExplicitSum);
			
		if (m_options.getBalanceBinaryExpressions ())
			return balancedBinaryExpression (listSummandsSimplified, BinaryOperator.ADD);
			
		// don't balance expressions
		return leftToRightBinaryExpression (listSummandsSimplified, BinaryOperator.ADD);
	}
	
	/**
	 * Create a balanced product expression.
	 */
	private ExpressionData product (List<ExpressionData> listFactors, boolean bIsInteger)
	{
		List<ExpressionData> listFactorsSimplified = new ArrayList<> (listFactors.size ());
		double fProduct = 1;
		for (ExpressionData expr : listFactors)
		{
			if (ExpressionUtil.isNumberLiteral (expr.getExpression ()))
				fProduct *= ExpressionUtil.getFloatValue (expr.getExpression ());
			else
				listFactorsSimplified.add (expr);
		}
		
		ExpressionData exprExplicitProduct = new ExpressionData (createLiteral (fProduct, bIsInteger), 0, Symbolic.EExpressionType.EXPRESSION);
		if (listFactorsSimplified.size () == 0)
			return exprExplicitProduct;
		
		if (fProduct != 1)
			listFactorsSimplified.add (exprExplicitProduct);
			
		if (m_options.getBalanceBinaryExpressions ())
			return balancedBinaryExpression (listFactorsSimplified, BinaryOperator.MULTIPLY);

		// don't balance expressions
		return leftToRightBinaryExpression (listFactorsSimplified, BinaryOperator.MULTIPLY);

	}
	
	private ExpressionData balancedBinaryExpression (List<ExpressionData> listOperands, BinaryOperator op)
	{
		if (listOperands.size () == 0)
			return new ExpressionData (new IntegerLiteral (0), 0, Symbolic.EExpressionType.EXPRESSION);
		if (listOperands.size () == 1)
			return listOperands.get (0);
			
		ExpressionData exprLeft = balancedBinaryExpression (listOperands.subList (0, listOperands.size () / 2), op);
		ExpressionData exprRight = balancedBinaryExpression (listOperands.subList (listOperands.size () / 2, listOperands.size ()), op);

		return new ExpressionData (
			new BinaryExpression (exprLeft.getExpression (), op, exprRight.getExpression ()),
			exprLeft.getFlopsCount () + 1 + exprRight.getFlopsCount (),
			Symbolic.EExpressionType.EXPRESSION);
	}
	
	private ExpressionData leftToRightBinaryExpression (List<ExpressionData> listOperands, BinaryOperator op)
	{
		Expression exprSum = null;
		int nFlops = 0;
		for (ExpressionData expr : listOperands)
		{
			if (exprSum == null)
				exprSum = expr.getExpression ();
			else
			{
				exprSum = new BinaryExpression (exprSum.clone (), op, expr.getExpression ());
				nFlops++;
			}
			nFlops += expr.getFlopsCount ();
		}
		
		return new ExpressionData (exprSum, nFlops, Symbolic.EExpressionType.EXPRESSION);	
	}
	
	private ExpressionData subtract (ExpressionData expr1, ExpressionData expr2, boolean bIsInteger)
	{
		if (ExpressionUtil.isNumberLiteral (expr1.getExpression ()) && ExpressionUtil.isNumberLiteral (expr2.getExpression ()))
		{
			return new ExpressionData (
				createLiteral (ExpressionUtil.getFloatValue (expr1.getExpression ()) - ExpressionUtil.getFloatValue (expr2.getExpression ()), bIsInteger),
				0,
				Symbolic.EExpressionType.EXPRESSION);
		}
			
		return new ExpressionData (
			new BinaryExpression (expr1.getExpression (), BinaryOperator.SUBTRACT, expr2.getExpression ()),
			expr1.getFlopsCount () + 1 + expr2.getFlopsCount (),
			Symbolic.EExpressionType.EXPRESSION);
	}
	
	private ExpressionData divide (ExpressionData expr1, ExpressionData expr2, boolean bIsInteger)
	{
		if (ExpressionUtil.isNumberLiteral (expr1.getExpression ()) && ExpressionUtil.isNumberLiteral (expr2.getExpression ()))
		{
			return new ExpressionData (
				createLiteral (ExpressionUtil.getFloatValue (expr1.getExpression ()) / ExpressionUtil.getFloatValue (expr2.getExpression ()), bIsInteger),
				0,
				Symbolic.EExpressionType.EXPRESSION);
		}
			
		return new ExpressionData (
			new BinaryExpression (expr1.getExpression (), BinaryOperator.DIVIDE, expr2.getExpression ()),
			expr1.getFlopsCount () + 1 + expr2.getFlopsCount (),
			Symbolic.EExpressionType.EXPRESSION);
	}
	
	private ExpressionData modulus (ExpressionData expr1, ExpressionData expr2, boolean bIsInteger)
	{
		if (ExpressionUtil.isNumberLiteral (expr1.getExpression ()) && ExpressionUtil.isNumberLiteral (expr2.getExpression ()))
		{
			return new ExpressionData (
				createLiteral (ExpressionUtil.getIntegerValue (expr1.getExpression ()) % ExpressionUtil.getIntegerValue (expr2.getExpression ()), bIsInteger),
				0,
				Symbolic.EExpressionType.EXPRESSION);
		}
			
		return new ExpressionData (
			new BinaryExpression (expr1.getExpression (), BinaryOperator.MODULUS, expr2.getExpression ()),
			expr1.getFlopsCount () + 1 + expr2.getFlopsCount (),
			Symbolic.EExpressionType.EXPRESSION);
	}
	
	
	/**
	 * Registers a stream along with its dimensions mapping it to the normalized internal representation.
	 * @param strIdentifier The identifier by which the stream is referred to in the source code
	 * @param box The dimensions of the stream box
	 * @param listDimensions List of dimensions of the stream
	 * @param sd The stream direction specifying whether this is an input or an output stream, i.e. is read from or written to
	 */
	private void registerStream (String strIdentifier, Specifier specType, boolean bIsConstant, Box box, List<Integer> listDimensions, EStreamDirection sd)
	{
		// lazily create the maps
		if (m_mapInputStreams == null)
			m_mapInputStreams = new HashMap<> ();
		if (m_mapOutputStreams == null)
			m_mapOutputStreams = new HashMap<> ();
		if (m_listStencilOperationArguments == null)
			m_listStencilOperationArguments = new ArrayList<> ();
		
		Map<String, StreamIndex> map = sd == EStreamDirection.INPUT ? m_mapInputStreams : m_mapOutputStreams;
		if (!map.containsKey (strIdentifier))
			map.put (strIdentifier, new StreamIndex (strIdentifier, specType, bIsConstant, box, listDimensions, sd));
			
		if (sd == EStreamDirection.INPUT)
			m_listStencilOperationArguments.add (strIdentifier);
	}
	
	private void registerScalar (String strIdentifier, Specifier specType, List<Integer> listDimensions, boolean bIsStencilArgument)
	{
		if (m_mapScalars == null)
			m_mapScalars = new HashMap<> ();
		if (m_listStencilOperationArguments == null)
			m_listStencilOperationArguments = new ArrayList<> ();
			
		if (!m_mapScalars.containsKey (strIdentifier))
			m_mapScalars.put (strIdentifier, new StencilCalculation.ParamType (specType, listDimensions));
			
		if (bIsStencilArgument)
			m_listStencilOperationArguments.add (strIdentifier);
	}
	
	private void registerConstant (String strIdentifier, Literal litValue)
	{
	   if (m_mapConstants == null)
	       m_mapConstants = new HashMap<> ();
	   m_mapConstants.put (strIdentifier, litValue.clone ());
	}
	
	private Literal getConstantValue (String strIdentifier)
	{
	   if (m_mapConstants == null)
	       return null;
	       
	   Literal litValue = m_mapConstants.get (strIdentifier);
	   return litValue == null ? null : litValue.clone ();
	}
	
	/**
	 * Returns the internal stream index given the identifier in the source code and indices.
	 * @param strIdentifier The identifier in the source code
	 * @param listIndices A list of indices (subscripts)
	 * @param sd The stream direction specifying whether this is an input or an output stream, i.e. is read from or written to
	 * @return The internal stream index
	 */
	private int getStreamIndex (String strIdentifier, List<Integer> listIndices, EStreamDirection sd)
	{
		Map<String, StreamIndex> map = sd == EStreamDirection.INPUT ? m_mapInputStreams : m_mapOutputStreams;
		StreamIndex idx = map.get (strIdentifier);
		
		if (idx != null)
			return idx.getLinearIndex (listIndices);
			
		errors.SemErr (la.line, la.col, "Variable '" + strIdentifier + "' has not been defined");
		return -1;		
	}
	
	private void checkParameterIndices (String strIdentifier, Expression exprParam)
	{
		StencilCalculation.ParamType param = m_mapScalars == null ? null : m_mapScalars.get (strIdentifier);
		if (param == null)
		{
			// the key hasn't been found => parameter is not defined
			errors.SemErr (la.line, la.col, "Parameter '" + strIdentifier + "' has not been defined");
			return;
		}
		
		// check bounds
		if (exprParam instanceof ArrayAccess)
		{
			ArrayAccess arr = (ArrayAccess) exprParam;
			
			// check whether the dimensions agree
			if (param.getDimensionsCount () != arr.getNumIndices ())
			{
				errors.SemErr (la.line, la.col, "Parameter dimension does not agree with its definition (should be " + param.getDimensionsCount () + ")");
				return;
			}
			
			// check bounds
			int i = 0;
			for (int nDim : param.getDimensions ())
			{
				int nIdx = getInteger (arr.getIndex (i));
				if (nIdx < 0 || nIdx >= nDim)
				{
					errors.SemErr (la.line, la.col, "Index out of bounds (should be 0.." + (nDim - 1) + ")");
					return;
				}
				i++;
			}
		}
		else
		{
			// not an array access => the number of dimensions must be 0
			if (param.getDimensionsCount () != 0)
				errors.SemErr (la.line, la.col, "Parameter dimension does not agree with its definition (should be 0)");
		}
	}
	
	private void addSizeParameters (Box box)
	{
		for (Expression expr : box.getMin ())
			addSizeParameter (expr);
		for (Expression expr : box.getMax ())
			addSizeParameter (expr);
	}
	
	private void addSizeParameter (Expression expr)
	{	
		for (DepthFirstIterator it = new DepthFirstIterator (expr); it.hasNext (); )
		{
			Object o = it.next ();
			if (o instanceof NameID)
			{
				if (m_listSizeParameters == null)
					m_listSizeParameters = new LinkedList<NameID> ();
			
				if (!m_listSizeParameters.contains (o))				
					m_listSizeParameters.add ((NameID) o);
			}
		}		
	}
	
	private int getInteger (Expression expr)
	{
		try
		{
			return ExpressionUtil.getIntegerValue (expr);
		}
		catch (RuntimeException e)
		{
        	errors.SemErr (la.line, la.col, "Indices must evaluate to constant numbers");
        }
        
        return 0;
	}

	/**
	 * Adds the parameters to the StencilCalculation object 
	 */	
	private void setStencilOperationArguments ()
	{
        if (m_listStencilOperationArguments != null)
        {
            for (String strIdentifier : m_listStencilOperationArguments)
            {
                StreamIndex idx =  m_mapInputStreams.get (strIdentifier);
                m_stencil.addStencilOperationArgument (
                    strIdentifier,
                    idx == null ? null : idx.getStreamDimensions (),
                    m_mapScalars == null ? null : m_mapScalars.get (strIdentifier));
            }
        }
        
        if (m_listSizeParameters != null)
        {
        	for (NameID nid : m_listSizeParameters)
        		m_stencil.addSizeParameter (nid);
        }
	}
	
	private void checkDefinitions ()
	{
		if (m_stencil.getDomainSize () == null)
			errors.SemErr ("No domainsize defined in the stencil specification.");
		if (!m_bIterateWhileSet)
			errors.SemErr ("No 'iterate while ...' defined in the stencil specification.");
		if (m_stencil.getStencilBundle () == null)
			errors.SemErr ("No stencil operation defined in the stencil specification.");
	}
	
	private boolean containsStencilNode (Expression expr)
	{
		for (DepthFirstIterator it = new DepthFirstIterator (expr); it.hasNext (); )
			if (it.next () instanceof StencilNode)
				return true;
		return false;
	}
	

	///////////////////////////////////////////////////////////////////////////
	// LL(1) Conflict Resolvers
	
	/**
	 * Determines whether the next token is a grid variable.
	 * @return <code>true</code> iff the next token is a grid variable
	 */
	private boolean isGridVariable ()
	{
		String strIdentifier = la.val;
		boolean bResult = m_mapInputStreams == null ? false : m_mapInputStreams.containsKey (strIdentifier);
		if (bResult)
			return true;
		bResult = m_mapOutputStreams == null ? false : m_mapOutputStreams.containsKey (strIdentifier);
		if (bResult)
			return true;
			
		return false;
	}
	
	/**
	 * Determines whether the next token is a const grid variable.
	 * @return <code>true</code> iff the next token is a const grid variable
	 */
	private boolean isConstGridVariable ()
	{
		String strIdentifier = la.val;
		boolean bResult = m_mapInputStreams == null ? false : m_mapInputStreams.containsKey (strIdentifier);
		if (bResult)
		{
			StreamIndex idx = m_mapInputStreams.get (strIdentifier);
			return idx.isConstant ();
		}
			
		return false;
	}
	
	/**
	 * Determines whether the next token is a scalar: either a stencil operation parameter or a
	 * temporary variable defined within the stencil operation body.
	 * @return <code>true</code> iff the next token is parameter
	 */
	private boolean isScalar ()
	{
		if (m_mapScalars == null)
			return false;

		String strIdentifier = la.val;
		return m_mapScalars.containsKey (strIdentifier);
	}

	/**
	 * LL1 conflict resolver for function calls.
	 */
	private boolean isFunctionCall ()
	{
		Token t = scanner.Peek ();
		scanner.ResetPeek ();
		return t.val.equals ("(");
	}


///////////////////////////////////////////////////////////////////////////
// Tokens



	public Parser(Scanner scanner) {
		this.scanner = scanner;
		errors = new Errors();
	}

	void SynErr (int n) {
		if (errDist >= minErrDist) errors.SynErr(la.line, la.col, n);
		errDist = 0;
	}

	public void SemErr (String msg) {
		if (errDist >= minErrDist) errors.SemErr(t.line, t.col, msg);
		errDist = 0;
	}
	
	void Get () {
		for (;;) {
			t = la;
			la = scanner.Scan();
			if (la.kind <= maxT) {
				++errDist;
				break;
			}

			la = t;
		}
	}
	
	void Expect (int n) {
		if (la.kind==n) Get(); else { SynErr(n); }
	}
	
	boolean StartOf (int s) {
		return set[s][la.kind];
	}
	
	void ExpectWeak (int n, int follow) {
		if (la.kind == n) Get();
		else {
			SynErr(n);
			while (!StartOf(follow)) Get();
		}
	}
	
	boolean WeakSeparator (int n, int syFol, int repFol) {
		int kind = la.kind;
		if (kind == n) { Get(); return true; }
		else if (StartOf(repFol)) return false;
		else {
			SynErr(n);
			while (!(set[syFol][kind] || set[repFol][kind] || set[0][kind])) {
				Get();
				kind = la.kind;
			}
			return StartOf(syFol);
		}
	}
	
	void StencilSpecification() {
		while (!(la.kind == 0 || la.kind == 3)) {SynErr(52); Get();}
		Expect(3);
		Expect(1);
		m_stencil = new StencilCalculation (t.val); 
		Expect(4);
		StencilOperationParamList();
		Expect(5);
		Expect(6);
		while (StartOf(1)) {
			switch (la.kind) {
			case 8: {
				StencilOptions();
				break;
			}
			case 14: {
				Expression exprIterateWhile = StencilIterateWhile();
				if (m_bIterateWhileSet) errors.SemErr (la.line, la.col, "Found multiple 'iterate while ...' definitions. All but the first one are ignored."); else m_bIterateWhileSet = true; 
				break;
			}
			case 16: {
				Box boxGrid = StencilDomainSize();
				if (m_stencil.getDomainSize () != null) errors.SemErr (la.line, la.col, "Found multiple grid size definitions. All but the first one are ignored."); else m_stencil.setDomainSize (boxGrid); 
				break;
			}
			case 17: {
				StencilBundle bundle = StencilOperation();
				if (m_stencil.getStencilBundle () != null) errors.SemErr (la.line, la.col, "Found multiple stencil definitions. All but the first are ignored."); else m_stencil.setStencil (bundle); 
				break;
			}
			case 18: {
				StencilBundle bundle = StencilBoundaries();
				if (m_stencil.getBoundaries () != null) errors.SemErr ("Found multiple boundaries definitions. All but the first are ignored."); else m_stencil.setBoundaries (bundle); 
				break;
			}
			case 19: {
				StencilBundle bundle = StencilInitial();
				if (m_stencil.getInitialization () != null) errors.SemErr ("Found multiple initializations. All but the first are ignored."); else m_stencil.setInitialization (bundle); 
				break;
			}
			}
		}
		Expect(7);
		setStencilOperationArguments (); checkDefinitions (); 
	}

	void StencilOperationParamList() {
		StencilOperationParam();
		while (la.kind == 20) {
			Get();
			StencilOperationParam();
		}
	}

	void StencilOptions() {
		while (!(la.kind == 0 || la.kind == 8)) {SynErr(53); Get();}
		Expect(8);
		Expect(6);
		while (la.kind == 10) {
			StencilOptionsCompatibility();
			Expect(9);
		}
		Expect(7);
	}

	Expression  StencilIterateWhile() {
		Expression  exprIterateWhile;
		while (!(la.kind == 0 || la.kind == 14)) {SynErr(54); Get();}
		Expect(14);
		Expect(15);
		ExpressionData edIterateWhile = LogicalExpression(null,true,true);
		exprIterateWhile = edIterateWhile.getExpression (); 
		Expect(9);
		return exprIterateWhile;
	}

	Box  StencilDomainSize() {
		Box  boxGrid;
		while (!(la.kind == 0 || la.kind == 16)) {SynErr(55); Get();}
		Expect(16);
		Expect(11);
		boxGrid = StencilBox();
		Expect(9);
		return boxGrid;
	}

	StencilBundle  StencilOperation() {
		StencilBundle  bundle;
		bundle = new StencilBundle (m_stencil); 
		while (!(la.kind == 0 || la.kind == 17)) {SynErr(56); Get();}
		Expect(17);
		Expect(6);
		while (la.kind == 1 || la.kind == 23 || la.kind == 24) {
			AssignmentStatement(bundle);
		}
		Expect(7);
		return bundle;
	}

	StencilBundle  StencilBoundaries() {
		StencilBundle  bundle;
		bundle = new StencilBundle (m_stencil); 
		while (!(la.kind == 0 || la.kind == 18)) {SynErr(57); Get();}
		Expect(18);
		Expect(6);
		while (la.kind == 1 || la.kind == 23 || la.kind == 24) {
			AssignmentStatement(bundle);
		}
		Expect(7);
		return bundle;
	}

	StencilBundle  StencilInitial() {
		StencilBundle  bundle;
		bundle = new StencilBundle (m_stencil); 
		while (!(la.kind == 0 || la.kind == 19)) {SynErr(58); Get();}
		Expect(19);
		Expect(6);
		while (la.kind == 1 || la.kind == 23 || la.kind == 24) {
			AssignmentStatement(bundle);
		}
		Expect(7);
		return bundle;
	}

	void StencilOptionsCompatibility() {
		while (!(la.kind == 0 || la.kind == 10)) {SynErr(59); Get();}
		Expect(10);
		Expect(11);
		if (la.kind == 12) {
			Get();
			m_options.setCompatibility (CodeGenerationOptions.ECompatibility.C); 
		} else if (la.kind == 13) {
			Get();
			m_options.setCompatibility (CodeGenerationOptions.ECompatibility.FORTRAN); 
		} else SynErr(60);
	}

	ExpressionData  LogicalExpression(Stencil stencil, boolean bIsDeclaration, boolean bIsInteger) {
		ExpressionData  expr;
		expr = OrExpression(stencil, bIsDeclaration, bIsInteger);
		return expr;
	}

	Box  StencilBox() {
		Box  box;
		Expect(4);
		List<Expression> listMin = new ArrayList<Expression> (); List<Expression> listMax = new ArrayList<Expression> (); 
		Box box1 = StencilBoxCoordinate();
		listMin.add (box1.getMin ().getCoord (0)); listMax.add (box1.getMax ().getCoord (0)); 
		while (la.kind == 20) {
			Get();
			box1 = StencilBoxCoordinate();
			listMin.add (box1.getMin ().getCoord (0)); listMax.add (box1.getMax ().getCoord (0)); 
		}
		Expression[] rgMin = new Expression[listMin.size ()]; Expression[] rgMax = new Expression[listMax.size ()]; listMin.toArray (rgMin); listMax.toArray (rgMax); 
		Expect(5);
		box = new Box (new Point (rgMin), new Point (rgMax)); addSizeParameters (box); 
		return box;
	}

	void AssignmentStatement(StencilBundle bundle) {
		Stencil stencil = new Stencil (); 
		if (la.kind == 1) {
			ExpressionData exprAssign = StencilAssignment(stencil);
			stencil.setExpression (exprAssign); 
		} else if (la.kind == 23 || la.kind == 24) {
			ExpressionData exprAssign = ScalarAssignment(stencil);
			if (exprAssign != null) stencil.setExpression (exprAssign); 
		} else SynErr(61);
		while (!(la.kind == 0 || la.kind == 9)) {SynErr(62); Get();}
		Expect(9);
		try { if (!stencil.isEmpty ()) bundle.addStencil (stencil); } catch (NoSuchMethodException e) { e.printStackTrace (); }
	}

	Box  StencilBoxCoordinate() {
		Box  box;
		ExpressionData edMin = StencilExpression(null, true, true);
		Expect(21);
		ExpressionData edMax = StencilExpression(null, true, true);
		box = new Box (new Point (edMin.getExpression ()), new Point (edMax.getExpression ())); 
		return box;
	}

	ExpressionData  StencilExpression(Stencil stencil, boolean bIsDeclaration, boolean bIsInteger) {
		ExpressionData  expr;
		ExpressionData exprAdd = AdditiveExpression(stencil, bIsDeclaration, bIsInteger);
		expr = exprAdd; /*expr = exprAdd == null ? null : NormalExpression.simplify (exprAdd);*/ 
		return expr;
	}

	void StencilOperationParam() {
		boolean bIsGridVariable = true; boolean bIsConstant = false; Specifier specVarType = null; Box boxGrid = null; 
		if (la.kind == 22) {
			Get();
			bIsConstant = true; 
		}
		if (la.kind == 23) {
			Get();
			specVarType = Specifier.FLOAT; 
		} else if (la.kind == 24) {
			Get();
			specVarType = Specifier.DOUBLE; 
		} else if (la.kind == 25) {
			Get();
			specVarType = Specifier.INT; 
		} else if (la.kind == 26) {
			Get();
			specVarType = Specifier.LONG; 
		} else SynErr(63);
		if (la.kind == 27) {
			Get();
		} else if (la.kind == 28) {
			Get();
			bIsGridVariable = false; 
		} else SynErr(64);
		Expect(1);
		String strIdentifier = t.val; List<Integer> listDimensions = new ArrayList<Integer> (); 
		if (la.kind == 4) {
			boxGrid = StencilBox();
			if (!bIsGridVariable) errors.SemErr (la.line, la.col, "Parameters cannot have a box size declaration"); 
		}
		if (la.kind == 29) {
			Get();
			int nValue = IntegerLiteral();
			listDimensions.add (nValue); 
			while (la.kind == 20) {
				Get();
				nValue = IntegerLiteral();
				listDimensions.add (nValue); 
			}
			Expect(30);
		}
		if (bIsGridVariable) { 
		registerStream (strIdentifier, specVarType, bIsConstant, boxGrid, listDimensions, EStreamDirection.INPUT); 
		if (!bIsConstant) 
		registerStream (strIdentifier, specVarType, bIsConstant, boxGrid, listDimensions, EStreamDirection.OUTPUT); 
		} 
		else { 
		registerScalar (strIdentifier, specVarType, listDimensions, true); 
		m_stencil.preAddStencilOperationParameter (strIdentifier, specVarType); 
		} 
	}

	int  IntegerLiteral() {
		int  nValue;
		Expect(2);
		nValue = Integer.parseInt (t.val); 
		return nValue;
	}

	ExpressionData  StencilAssignment(Stencil stencil) {
		ExpressionData  exprAssignment;
		StencilNode nodeLHS = StencilIdentifier();
		stencil.addOutputNode (nodeLHS); 
		Expect(11);
		ExpressionData exprRHS = StencilExpression(stencil, false, false);
		exprAssignment = (nodeLHS == null || exprRHS == null) ? null : new ExpressionData (new AssignmentExpression (nodeLHS, AssignmentOperator.NORMAL, exprRHS.getExpression ()), exprRHS.getFlopsCount (), Symbolic.EExpressionType.EXPRESSION); 
		return exprAssignment;
	}

	ExpressionData  ScalarAssignment(Stencil stencil) {
		ExpressionData  exprAssignment;
		exprAssignment = null; Specifier specType = Specifier.FLOAT; 
		if (la.kind == 23) {
			Get();
		} else if (la.kind == 24) {
			Get();
			specType = Specifier.DOUBLE; 
		} else SynErr(65);
		Expect(1);
		String strIdentifier = t.val; 
		Expect(11);
		ExpressionData exprRHS = StencilExpression(stencil, false, false);
		Expression exprSimplified = null; 
		if (exprRHS.getExpression () instanceof FloatLiteral) 
		exprSimplified = exprRHS.getExpression (); 
		else if (!containsStencilNode (exprRHS.getExpression ())) 
		exprSimplified = Symbolic.simplify (exprRHS.getExpression ()); 
		if (exprSimplified instanceof FloatLiteral || exprSimplified instanceof IntegerLiteral) 
		registerConstant (strIdentifier, (Literal) exprSimplified); 
		else { 
		registerScalar (strIdentifier, specType, new ArrayList<Integer> (), false); StencilNode node = new StencilNode (strIdentifier, specType, null); stencil.addOutputNode (node); 
		exprAssignment = new ExpressionData (new AssignmentExpression (node, AssignmentOperator.NORMAL, exprRHS.getExpression ()), exprRHS.getFlopsCount (), Symbolic.EExpressionType.EXPRESSION); 
		} 
		return exprAssignment;
	}

	StencilNode  StencilIdentifier() {
		StencilNode  node;
		Expect(1);
		String strIdentifier = t.val; node = null; Index index = new Index (); 
		Expect(29);
		ExpressionData exprIdx0 = StencilExpression(null,false,true);
		int nMode = 0; List<Expression> listIndices = new ArrayList<> (); listIndices.add (exprIdx0); 
		while (la.kind == 9 || la.kind == 20) {
			while (!(la.kind == 0 || la.kind == 9 || la.kind == 20)) {SynErr(66); Get();}
			if (la.kind == 20) {
				Get();
			} else {
				Get();
				switch (nMode) { case 0: index.setSpaceIndex (listIndices); break; case 1: index.setTimeIndex (listIndices); break; case 2: index.setVectorIndex (getStreamIndex (strIdentifier, listIndices, EStreamDirection.XXX)); break; default: errors.SemErr (la.line, la.col, "Grids can't have more than 3 different index types."); } nMode++; listIndices = new ArrayList<> (); 
			}
			ExpressionData exprIdx1 = StencilExpression(null,false,true);
			listIndices.add (exprIdx1); 
		}
		Expect(30);
		switch (nMode) { case 0: index.setSpaceIndex (listIndices); break; case 1: index.setTimeIndex (listIndices); break; case 2: index.setVectorIndex (getStreamIndex (strIdentifier, listIndices, EStreamDirection.XXX)); break; default: errors.SemErr (la.line, la.col, "Grids can't have more than 3 different index types."); } 
		node = new StencilNode (strIdentifier, m_mapOutputStreams.get (strIdentifier).getSpecifier (), index); 
		return node;
	}

	Expression  ScalarIdentifier(boolean bIsDecl, boolean bIsInteger) {
		Expression  exprParam;
		Expect(1);
		String strIdentifier = t.val; Literal litValue = getConstantValue (strIdentifier); exprParam = litValue == null ? new NameID (strIdentifier) : litValue; 
		if (la.kind == 29) {
			Get();
			if ((exprParam instanceof FloatLiteral) || (exprParam instanceof IntegerLiteral)) errors.SemErr (la.line, la.col, "Cannot subscript a scalar value"); 
			ExpressionData exprIdx = StencilExpression(null, bIsDecl, true);
			exprParam = new ArrayAccess (exprParam.clone (), new IntegerLiteral (getInteger (exprIdx.getExpression ()))); 
			while (la.kind == 20) {
				while (!(la.kind == 0 || la.kind == 20)) {SynErr(67); Get();}
				Get();
				exprIdx = StencilExpression(null, bIsDecl, true);
				((ArrayAccess) exprParam).addIndex (new IntegerLiteral (getInteger (exprIdx.getExpression ()))); 
			}
			Expect(30);
		}
		if (!(exprParam instanceof FloatLiteral) && !(exprParam instanceof IntegerLiteral) && !bIsDecl) checkParameterIndices (strIdentifier, exprParam); 
		return exprParam;
	}

	ExpressionData  AdditiveExpression(Stencil stencil, boolean bIsDecl, boolean bIsInteger) {
		ExpressionData  expr;
		List<ExpressionData> listSummands = new LinkedList<ExpressionData> (); boolean bAdd = true; expr = null; 
		ExpressionData expr0 = MultiplicativeExpression(stencil, bIsDecl, bIsInteger);
		listSummands.add (expr0); 
		while (la.kind == 44 || la.kind == 45) {
			if (la.kind == 44) {
				Get();
				bAdd = true; 
			} else {
				Get();
				bAdd = false; expr = sum (listSummands, bIsInteger); listSummands.clear (); 
			}
			ExpressionData expr1 = MultiplicativeExpression(stencil, bIsDecl, bIsInteger);
			if (bAdd) listSummands.add (expr1); else listSummands.add (subtract (expr.clone (), expr1, bIsInteger)); 
		}
		expr = sum (listSummands, bIsInteger); 
		return expr;
	}

	ExpressionData  OrExpression(Stencil stencil, boolean bIsDecl, boolean bIsInteger) {
		ExpressionData  expr;
		ExpressionData expr0 = AndExpression(stencil, bIsDecl, bIsInteger);
		expr = expr0; 
		while (la.kind == 31 || la.kind == 32) {
			if (la.kind == 31) {
				Get();
			} else {
				Get();
			}
			ExpressionData expr1 = AndExpression(stencil,bIsDecl,bIsInteger);
			expr = new ExpressionData (new BinaryExpression (expr.getExpression (), BinaryOperator.LOGICAL_OR, expr1.getExpression ()), expr.getFlopsCount () + expr1.getFlopsCount (), Symbolic.EExpressionType.EXPRESSION); 
		}
		return expr;
	}

	ExpressionData  AndExpression(Stencil stencil, boolean bIsDecl, boolean bIsInteger) {
		ExpressionData  expr;
		ExpressionData expr0 = NotExpression(stencil, bIsDecl, bIsInteger);
		expr = expr0; 
		while (la.kind == 33 || la.kind == 34) {
			if (la.kind == 33) {
				Get();
			} else {
				Get();
			}
			ExpressionData expr1 = NotExpression(stencil,bIsDecl,bIsInteger);
			expr = new ExpressionData (new BinaryExpression (expr.getExpression (), BinaryOperator.LOGICAL_AND, expr1.getExpression ()), expr.getFlopsCount () + expr1.getFlopsCount (), Symbolic.EExpressionType.EXPRESSION); 
		}
		return expr;
	}

	ExpressionData  NotExpression(Stencil stencil, boolean bIsDecl, boolean bIsInteger) {
		ExpressionData  expr;
		UnaryOperator op = null; 
		if (la.kind == 35 || la.kind == 36) {
			if (la.kind == 35) {
				Get();
				op = UnaryOperator.LOGICAL_NEGATION; 
			} else {
				Get();
				op = UnaryOperator.LOGICAL_NEGATION; 
			}
		}
		expr = ComparisonExpression(stencil, bIsDecl, bIsInteger);
		if (op != null) expr = new ExpressionData (new UnaryExpression (op, expr.getExpression ()), expr.getFlopsCount (), Symbolic.EExpressionType.EXPRESSION); 
		return expr;
	}

	ExpressionData  ComparisonExpression(Stencil stencil, boolean bIsDecl, boolean bIsInteger) {
		ExpressionData  expr;
		BinaryOperator op0 = null; BinaryOperator op1 = null; 
		ExpressionData expr0 = AdditiveExpression(stencil, bIsDecl, bIsInteger);
		expr = expr0; 
		if (StartOf(2)) {
			op0 = ComparisonOperator();
			ExpressionData expr1 = AdditiveExpression(stencil, bIsDecl, bIsInteger);
			expr = new ExpressionData (new BinaryExpression (expr.getExpression (), op0, expr1.getExpression ()), expr.getFlopsCount () + expr1.getFlopsCount (), Symbolic.EExpressionType.EXPRESSION); 
			if (StartOf(2)) {
				op1 = ComparisonOperator();
				ExpressionData expr2 = AdditiveExpression(stencil, bIsDecl, bIsInteger);
				expr = new ExpressionData (new BinaryExpression (expr.getExpression (), BinaryOperator.LOGICAL_AND, new BinaryExpression (expr1.getExpression ().clone (), op1, expr2.getExpression ())), expr.getFlopsCount () + expr2.getFlopsCount (), Symbolic.EExpressionType.EXPRESSION); 
			}
		}
		return expr;
	}

	BinaryOperator  ComparisonOperator() {
		BinaryOperator  op;
		op = null; 
		switch (la.kind) {
		case 37: {
			Get();
			op = BinaryOperator.COMPARE_LT; 
			break;
		}
		case 38: {
			Get();
			op = BinaryOperator.COMPARE_LE; 
			break;
		}
		case 39: {
			Get();
			op = BinaryOperator.COMPARE_EQ; 
			break;
		}
		case 40: {
			Get();
			op = BinaryOperator.COMPARE_GE; 
			break;
		}
		case 41: {
			Get();
			op = BinaryOperator.COMPARE_GT; 
			break;
		}
		case 42: {
			Get();
			op = BinaryOperator.COMPARE_NE; 
			break;
		}
		case 43: {
			Get();
			op = BinaryOperator.COMPARE_NE; 
			break;
		}
		default: SynErr(68); break;
		}
		return op;
	}

	ExpressionData  MultiplicativeExpression(Stencil stencil, boolean bIsDecl, boolean bIsInteger) {
		ExpressionData  expr;
		List<ExpressionData> listFactors = new LinkedList<ExpressionData> (); BinaryOperator op = null; expr = null; 
		ExpressionData expr0 = UnarySignExpression(stencil, bIsDecl, bIsInteger);
		listFactors.add (expr0); 
		while (la.kind == 46 || la.kind == 47 || la.kind == 48) {
			if (la.kind == 46) {
				Get();
				op = BinaryOperator.MULTIPLY; 
			} else if (la.kind == 47) {
				Get();
				op = BinaryOperator.DIVIDE; 
			} else {
				Get();
				if (!bIsInteger) { errors.SemErr (la.line, la.col, "'%' is only defined for integers"); } 
				else { op = BinaryOperator.MODULUS; expr = product (listFactors, bIsInteger); listFactors.clear (); } 
			}
			ExpressionData expr1 = UnarySignExpression(stencil, bIsDecl, bIsInteger);
			if (op == BinaryOperator.MULTIPLY) listFactors.add (expr1); 
			else if (op == BinaryOperator.DIVIDE) { 
			if (expr1.getExpression () instanceof Literal) 
			listFactors.add (new ExpressionData (new FloatLiteral (1.0 / ExpressionUtil.getFloatValue (expr1.getExpression ())), 0, Symbolic.EExpressionType.EXPRESSION)); 
			else { 
			expr = product (listFactors, bIsInteger); listFactors.clear (); 
			listFactors.add (divide (expr.clone (), expr1, bIsInteger)); 
			} 
			} 
			else if (op == BinaryOperator.MODULUS) listFactors.add (modulus (expr.clone (), expr1, bIsInteger)); 
			else errors.SemErr (la.line, la.col, "No multiplicative operator defined"); 
		}
		expr = product (listFactors, bIsInteger); 
		return expr;
	}

	ExpressionData  UnarySignExpression(Stencil stencil, boolean bIsDecl, boolean bIsInteger) {
		ExpressionData  expr;
		boolean bIsNegative = false; 
		if (la.kind == 44 || la.kind == 45) {
			if (la.kind == 44) {
				Get();
			} else {
				Get();
				bIsNegative = true; 
			}
		}
		ExpressionData expr1 = ExponentExpression(stencil, bIsDecl, bIsInteger);
		if (!bIsNegative) expr = expr1; else { 
		if (expr1.getExpression () instanceof FloatLiteral) expr = new ExpressionData (new FloatLiteral (-((FloatLiteral) expr1.getExpression ()).getValue ()), 0, Symbolic.EExpressionType.EXPRESSION); 
		else if (expr1.getExpression () instanceof IntegerLiteral) expr = new ExpressionData (new IntegerLiteral (-((IntegerLiteral) expr1.getExpression ()).getValue ()), 0, Symbolic.EExpressionType.EXPRESSION); 
		else expr = new ExpressionData (new UnaryExpression (UnaryOperator.MINUS, expr1.getExpression ()), expr1.getFlopsCount () + 1, Symbolic.EExpressionType.EXPRESSION); 
		} 
		return expr;
	}

	ExpressionData  ExponentExpression(Stencil stencil, boolean bIsDecl, boolean bIsInteger) {
		ExpressionData  expr;
		expr = UnaryExpression(stencil, bIsDecl, bIsInteger);
		while (la.kind == 49) {
			Get();
			ExpressionData expr1 = UnaryExpression(stencil,bIsDecl,bIsInteger);
			expr = expr == null ? null : ExpressionUtil.createExponentExpression (expr.clone (), expr1); 
		}
		return expr;
	}

	ExpressionData  UnaryExpression(Stencil stencil, boolean bIsDecl, boolean bIsInteger) {
		ExpressionData  expr;
		expr = null; 
		if (StartOf(3)) {
			double fValue = NumberLiteral(bIsInteger);
			expr = new ExpressionData (createLiteral (fValue, bIsInteger), 0, Symbolic.EExpressionType.EXPRESSION); 
		} else if (isGridVariable ()) {
			StencilNode node = StencilIdentifier();
			expr = new ExpressionData (node, 0, Symbolic.EExpressionType.EXPRESSION); stencil.addInputNode (node); 
		} else if (la.kind == 4) {
			ExpressionData exprBracketed = BracketedExpression(stencil, bIsDecl, bIsInteger);
			expr = exprBracketed; 
		} else if (isFunctionCall ()) {
			ExpressionData exprFnxValue = FunctionCall(stencil, bIsDecl, bIsInteger);
			expr = exprFnxValue; 
		} else if (la.kind == 1) {
			Expression exprParam = ScalarIdentifier(bIsDecl, bIsInteger);
			expr = new ExpressionData (exprParam, 0, Symbolic.EExpressionType.EXPRESSION); 
		} else SynErr(69);
		return expr;
	}

	double  NumberLiteral(boolean bIsInteger) {
		double  fValue;
		fValue = 0.0; 
		if (!bIsInteger) {
			fValue = FloatLiteral();
		} else if (la.kind == 2) {
			Get();
			fValue = Integer.parseInt (t.val); 
		} else SynErr(70);
		return fValue;
	}

	ExpressionData  BracketedExpression(Stencil stencil, boolean bIsDeclaration, boolean bIsInteger) {
		ExpressionData  expr;
		Expect(4);
		expr = StencilExpression(stencil, bIsDeclaration, bIsInteger);
		Expect(5);
		return expr;
	}

	ExpressionData  FunctionCall(Stencil stencil, boolean bIsDecl, boolean bIsInteger) {
		ExpressionData  fnx;
		Expect(1);
		String strFunctionName = t.val; 
		Expect(4);
		List<Expression> listArgs = new ArrayList<Expression> (); int nFlopsCount = 0; 
		if (StartOf(4)) {
			ExpressionData expr = StencilExpression(stencil, bIsDecl, bIsInteger);
			listArgs.add (expr.getExpression ()); nFlopsCount += expr.getFlopsCount (); 
			while (la.kind == 20) {
				while (!(la.kind == 0 || la.kind == 20)) {SynErr(71); Get();}
				Get();
				expr = StencilExpression(stencil, bIsDecl, bIsInteger);
				listArgs.add (expr.getExpression ()); nFlopsCount += expr.getFlopsCount (); 
			}
		}
		Expect(5);
		fnx = new ExpressionData (new FunctionCall (new NameID (strFunctionName), listArgs), nFlopsCount + 1, Symbolic.EExpressionType.EXPRESSION); 
		return fnx;
	}

	int  OffsetExpression() {
		int  nValue;
		boolean bIsNegative = false; 
		if (la.kind == 44) {
			Get();
		} else if (la.kind == 45) {
			Get();
			bIsNegative = true; 
		} else SynErr(72);
		Expect(2);
		nValue = Integer.parseInt (t.val); if (bIsNegative) nValue = -nValue; 
		return nValue;
	}

	double  FloatLiteral() {
		double  fValue;
		StringBuilder sb = new StringBuilder (); 
		if (la.kind == 2) {
			Get();
			sb.append (t.val); 
		}
		if (la.kind == 50) {
			Get();
			sb.append (t.val); 
			if (la.kind == 2) {
				Get();
				sb.append (t.val); 
			}
		}
		fValue = Double.parseDouble (sb.toString ()); 
		return fValue;
	}



	public void Parse() {
		la = new Token();
		la.val = "";		
		Get();
		StencilSpecification();
		Expect(0);

	}

	private static final boolean[][] set = {
		{T,x,x,T, x,x,x,x, T,T,T,x, x,x,T,x, T,T,T,T, T,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x},
		{x,x,x,x, x,x,x,x, T,x,x,x, x,x,T,x, T,T,T,T, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x},
		{x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,T,T,T, T,T,T,T, x,x,x,x, x,x,x,x, x},
		{x,x,T,x, x,T,x,x, x,T,x,x, x,x,x,x, x,x,x,x, T,T,x,x, x,x,x,x, x,x,T,T, T,T,T,x, x,T,T,T, T,T,T,T, T,T,T,T, T,T,T,x, x},
		{x,T,T,x, T,T,x,x, x,x,x,x, x,x,x,x, x,x,x,x, T,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, x,x,x,x, T,T,T,T, T,T,T,x, x}

	};
} // end Parser


class Errors {
	public int count = 0;                                    // number of errors detected
	public String errMsgFormat = "Line {0}, col {1}: {2}"; // 0=line, 1=column, 2=text
	
	protected void printMsg(int line, int column, String msg) {
		StringBuffer b = new StringBuffer(errMsgFormat);
		int pos = b.indexOf("{0}");
		if (pos >= 0) { b.delete(pos, pos+3); b.insert(pos, line); }
		pos = b.indexOf("{1}");
		if (pos >= 0) { b.delete(pos, pos+3); b.insert(pos, column); }
		pos = b.indexOf("{2}");
		if (pos >= 0) b.replace(pos, pos+3, msg);
		Parser.LOGGER.error (b.toString());
	}
	
	public void SynErr (int line, int col, int n) {
		String s;
		switch (n) {
			case 0: s = "EOF expected"; break;
			case 1: s = "ident expected"; break;
			case 2: s = "integer expected"; break;
			case 3: s = "\"stencil\" expected"; break;
			case 4: s = "\"(\" expected"; break;
			case 5: s = "\")\" expected"; break;
			case 6: s = "\"{\" expected"; break;
			case 7: s = "\"}\" expected"; break;
			case 8: s = "\"options\" expected"; break;
			case 9: s = "\";\" expected"; break;
			case 10: s = "\"compatibility\" expected"; break;
			case 11: s = "\"=\" expected"; break;
			case 12: s = "\"C/C++\" expected"; break;
			case 13: s = "\"Fortran\" expected"; break;
			case 14: s = "\"iterate\" expected"; break;
			case 15: s = "\"while\" expected"; break;
			case 16: s = "\"domainsize\" expected"; break;
			case 17: s = "\"operation\" expected"; break;
			case 18: s = "\"boundaries\" expected"; break;
			case 19: s = "\"initial\" expected"; break;
			case 20: s = "\",\" expected"; break;
			case 21: s = "\"..\" expected"; break;
			case 22: s = "\"const\" expected"; break;
			case 23: s = "\"float\" expected"; break;
			case 24: s = "\"double\" expected"; break;
			case 25: s = "\"int\" expected"; break;
			case 26: s = "\"long\" expected"; break;
			case 27: s = "\"grid\" expected"; break;
			case 28: s = "\"param\" expected"; break;
			case 29: s = "\"[\" expected"; break;
			case 30: s = "\"]\" expected"; break;
			case 31: s = "\"||\" expected"; break;
			case 32: s = "\"or\" expected"; break;
			case 33: s = "\"&&\" expected"; break;
			case 34: s = "\"and\" expected"; break;
			case 35: s = "\"!\" expected"; break;
			case 36: s = "\"not\" expected"; break;
			case 37: s = "\"<\" expected"; break;
			case 38: s = "\"<=\" expected"; break;
			case 39: s = "\"==\" expected"; break;
			case 40: s = "\">=\" expected"; break;
			case 41: s = "\">\" expected"; break;
			case 42: s = "\"!=\" expected"; break;
			case 43: s = "\"/=\" expected"; break;
			case 44: s = "\"+\" expected"; break;
			case 45: s = "\"-\" expected"; break;
			case 46: s = "\"*\" expected"; break;
			case 47: s = "\"/\" expected"; break;
			case 48: s = "\"%\" expected"; break;
			case 49: s = "\"^\" expected"; break;
			case 50: s = "\".\" expected"; break;
			case 51: s = "??? expected"; break;
			case 52: s = "this symbol not expected in StencilSpecification"; break;
			case 53: s = "this symbol not expected in StencilOptions"; break;
			case 54: s = "this symbol not expected in StencilIterateWhile"; break;
			case 55: s = "this symbol not expected in StencilDomainSize"; break;
			case 56: s = "this symbol not expected in StencilOperation"; break;
			case 57: s = "this symbol not expected in StencilBoundaries"; break;
			case 58: s = "this symbol not expected in StencilInitial"; break;
			case 59: s = "this symbol not expected in StencilOptionsCompatibility"; break;
			case 60: s = "invalid StencilOptionsCompatibility"; break;
			case 61: s = "invalid AssignmentStatement"; break;
			case 62: s = "this symbol not expected in AssignmentStatement"; break;
			case 63: s = "invalid StencilOperationParam"; break;
			case 64: s = "invalid StencilOperationParam"; break;
			case 65: s = "invalid ScalarAssignment"; break;
			case 66: s = "this symbol not expected in StencilIdentifier"; break;
			case 67: s = "this symbol not expected in ScalarIdentifier"; break;
			case 68: s = "invalid ComparisonOperator"; break;
			case 69: s = "invalid UnaryExpression"; break;
			case 70: s = "invalid NumberLiteral"; break;
			case 71: s = "this symbol not expected in FunctionCall"; break;
			case 72: s = "invalid OffsetExpression"; break;
			default: s = "error " + n; break;
		}
		printMsg(line, col, s);
		count++;
	}

	public void SemErr (int line, int col, String s) {	
		printMsg(line, col, s);
		count++;
	}
	
	public void SemErr (String s) {
		Parser.LOGGER.error (s);
		count++;
	}
	
	public void Warning (int line, int col, String s) {	
		printMsg(line, col, s);
	}
	
	public void Warning (String s) {
		Parser.LOGGER.error (s);
	}
} // Errors


class FatalError extends RuntimeException {
	public static final long serialVersionUID = 1L;
	public FatalError(String s) { super(s); }
}
