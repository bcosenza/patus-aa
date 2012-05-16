package ch.unibas.cs.hpwc.patus.codegen.backend.assembly.analyze;

import ch.unibas.cs.hpwc.patus.codegen.backend.assembly.IInstruction;
import ch.unibas.cs.hpwc.patus.codegen.backend.assembly.InstructionList;

public class DependenceAnalysis
{
	///////////////////////////////////////////////////////////////////
	// Constants
	
	
	///////////////////////////////////////////////////////////////////
	// Member Variables
	
	//private IArchitectureDescription m_arch;

	/**
	 * The array of instructions to analyze
	 */
	private IInstruction[] m_rgInstructions;
		
	
	///////////////////////////////////////////////////////////////////
	// Implementation

	public DependenceAnalysis (InstructionList il)
	{
		m_rgInstructions = new IInstruction[il.size ()];
		int j = 0;
		for (IInstruction instr : il)
		{
			m_rgInstructions[j] = instr;
			j++;
		}
	}
	
	public DAGraph run ()
	{
		DAGraph graph = new DAGraph ();
		DAGraph.Vertex[] rgVertices = new DAGraph.Vertex[m_rgInstructions.length];
		
		for (int i = 0; i < m_rgInstructions.length; i++)
		{
			graph.addVertex (rgVertices[i] = new DAGraph.Vertex (m_rgInstructions[i]));
			for (int j = 0; j < i; j++)
			{
				// does the current instruction (i) depend on a previous one (j)?
				if (InstructionListAnalyzer.isFlowDependent (m_rgInstructions[j], m_rgInstructions[i]))
					graph.addEdge (rgVertices[j], rgVertices[i]);
			}
		}
		
		return graph;
	}	
}