package org.evosuite.graphs.dataflow;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.classpath.ResourceList;
import org.evosuite.coverage.dataflow.DefUseFactory;
import org.evosuite.coverage.dataflow.DefUsePool;
import org.evosuite.coverage.dataflow.Definition;
import org.evosuite.coverage.dataflow.Use;
import org.evosuite.coverage.fbranch.FBranchDefUseAnalyzer;
import org.evosuite.graphs.GraphPool;
import org.evosuite.graphs.cfg.ActualControlFlowGraph;
import org.evosuite.graphs.cfg.BytecodeAnalyzer;
import org.evosuite.graphs.cfg.BytecodeInstruction;
import org.evosuite.graphs.cfg.ControlDependency;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.evosuite.setup.DependencyAnalysis;
import org.evosuite.utils.CollectionUtil;
import org.evosuite.utils.Randomness;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.objectweb.asm.tree.analysis.Value;

public class FieldUseAnalyzer {
	private Map<BytecodeInstruction, DepVariable> instructionPool = new HashMap<BytecodeInstruction, DepVariable>();
	
	
	private String getRuleBasedSubclass(String className) {
		if(className.equals("java.util.List")) {
			return "java.util.ArrayList";
		}
		else if(className.equals("java.util.Set")) {
			return "java.util.HashSet";
		}
		
		Set<String> subclasses = DependencyAnalysis.getInheritanceTree().getSubclasses(className);
		className = Randomness.choice(subclasses);
		
		return className;
	}
	
	private Map<String, Set<DepVariable>> analyzeReturnValueFromMethod(BytecodeInstruction instruction, 
			String recommendedClass){
		InstrumentingClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
		
		String className = instruction.getCalledMethodsClass();
		String methodName = instruction.getCalledMethod();
		
//		try {
//			Class<?> clazz = TestGenerationContext.getInstance().getClassLoaderForSUT().loadClass(className);
//			if(clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
//				className = recommendedClass==null ? getRuleBasedSubclass(className) : recommendedClass;
//			}
//		} catch (ClassNotFoundException e1) {
//			return new HashMap<>();
//		}
		
		
		ActualControlFlowGraph calledCfg = GraphPool.getInstance(classLoader).getActualCFG(className, methodName);
		MethodNode innerNode = getMethodNode(classLoader, className, methodName);
		if (calledCfg == null) {
			BytecodeAnalyzer bytecodeAnalyzer = new BytecodeAnalyzer();
			try {
				bytecodeAnalyzer.analyze(classLoader, className, methodName, innerNode);
			} catch (Exception e) {
				/**
				 * the cfg (e.g., jdk/library class) is out of our consideration
				 */
				return new HashMap<>();
			}
			Properties.ALWAYS_REGISTER_BRANCH = true;
			bytecodeAnalyzer.retrieveCFGGenerator().registerCFGs();
			calledCfg = GraphPool.getInstance(classLoader).getActualCFG(className, methodName);
			Properties.ALWAYS_REGISTER_BRANCH = false;
		}
		boolean canBeAnalyzed = FBranchDefUseAnalyzer.analyze(calledCfg.getRawGraph());
		if(!canBeAnalyzed) {
			return new HashMap<>();
		}
		
		Set<DepVariable> allDepVars = new HashSet<DepVariable>();
		Set<BytecodeInstruction> visitedIns = new HashSet<BytecodeInstruction>();
		for (BytecodeInstruction exit : calledCfg.getExitPoints()) {
			BytecodeInstruction returnInput = exit.getPreviousInstruction();
			searchDepedantVariables(returnInput, calledCfg, allDepVars, visitedIns);
		}
		
		HashMap<String, Set<DepVariable>> map = new HashMap<String, Set<DepVariable>>();
		map.put(className, allDepVars);
		return map;
	}
	
	
	
	/**
	 * value is written by {@code defIns}
	 * @param value
	 * @param defIns
	 * @return
	 */
	public DepVariable parseVariable(BytecodeInstruction defIns) {
		String className = defIns.getClassName();
		String varName = "$unknown";
		
		DepVariable var = instructionPool.get(defIns); 
		if(var == null) {
			var = new DepVariable(className, varName, defIns);
			
			instructionPool.put(defIns, var);
		}
		
		return var;
	}
	
	/**
	 * 
	 * This method collects all the variable depended by {@code value} and put them into {@code allDepVars}
	 * {@code depVars}
	 * 
	 * This search algorithm should take a walk through data flows until we find static field, instance field, or parameter.
	 * We would like to take the following usage into account:
	 * a.m1(x1, x2).m2(x3).f
	 * in which a can be either a static field, instance field, or parameter.
	 * 
	 * @param value
	 * @param cfg
	 * @param allLeafDepVars
	 * @param visitedIns
	 * @return
	 */
	public void searchDependantVariables(Value value, ActualControlFlowGraph cfg, Set<DepVariable> allLeafDepVars,
			Set<BytecodeInstruction> visitedIns) {
		
		if (!(value instanceof SourceValue)) {
			return;
		}
		
		String className = cfg.getClassName();
		String methodName = cfg.getMethodName();
		InstrumentingClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
		MethodNode node = getMethodNode(classLoader, className, methodName);
		
		SourceValue srcValue = (SourceValue) value;
		/**
		 * get all the instruction defining the value.
		 */
		for(AbstractInsnNode insNode: srcValue.insns) {
			BytecodeInstruction defIns = convert2BytecodeInstruction(cfg, node, insNode);
			searchDepedantVariables(defIns, cfg, allLeafDepVars, visitedIns);
		}
	}
	
	@SuppressWarnings("rawtypes")
	private void searchDepedantVariables(BytecodeInstruction defIns, ActualControlFlowGraph cfg, Set<DepVariable> allLeafDepVars,
			Set<BytecodeInstruction> visitedIns) {
		if (visitedIns.contains(defIns)) {
			return;
		}
		visitedIns.add(defIns);
		
		String className = cfg.getClassName();
		String methodName = cfg.getMethodName();
		InstrumentingClassLoader classLoader = TestGenerationContext.getInstance().getClassLoaderForSUT();
		MethodNode node = getMethodNode(classLoader, className, methodName);
		
		DepVariable outputVar = parseVariable(defIns);
		/**
		 * the variable is computed by values on stack
		 */
		List<DepVariable>[] intputVarArray = buildInputOutputForInstruction(defIns, node,
				outputVar, cfg, allLeafDepVars, visitedIns);
		
		/**
		 * if defIns is a method call, we need to explore more potential variables (fields) inside the method.
		 */
		if(defIns.isMethodCall() || defIns.isConstructorInvocation()) {
			String recommnedClass = outputVar.getRecommendedImplementation();
			recommnedClass = exploreInterproceduralInstruction(allLeafDepVars, defIns, intputVarArray, recommnedClass);
			outputVar.setRecommendedImplementation(recommnedClass);
		}
		
		/**
		 *  handle load/get static, need to put the variable into the return list
		 */
		if(outputVar.isStaticField() || outputVar.isInstaceField()) {
			allLeafDepVars.add(outputVar);
		}
		
		if(outputVar.referenceToThis() || outputVar.isParameter() || outputVar.isStaticField()) {
			//return;
		}
		
		/**
		 * the variable is computed by local variables
		 */
		if (defIns.isLocalVariableUse()){
			//keep traverse
			DefUseAnalyzer defUseAnalyzer = new DefUseAnalyzer();
			defUseAnalyzer.analyze(classLoader, node, className, methodName, node.access);
			Use use = DefUseFactory.makeUse(defIns);
			// Ignore method parameter
			List<Definition> defs = DefUsePool.getDefinitions(use);
			for (Definition def : CollectionUtil.nullToEmpty(defs)) {
				if (def != null) {
					BytecodeInstruction defInstruction = convert2BytecodeInstruction(cfg, node, def.getASMNode());
					buildInputOutputForInstruction(defInstruction, node,
							outputVar, cfg, allLeafDepVars, visitedIns);
				}
			}
		}
		
		/**
		 * handle control flow, note that there is no need to build data flow
		 */
		try {
			for(ControlDependency control: defIns.getControlDependencies()) {
				BytecodeInstruction controlIns = control.getBranch().getInstruction();
				int operandNum = controlIns.getOperandNum();
				for (int i = 0; i < operandNum; i++) {
					Frame frame = controlIns.getFrame();
					int index = frame.getStackSize() - operandNum + i ;
					Value val = frame.getStack(index);
					searchDependantVariables(val, cfg, allLeafDepVars, visitedIns);
				}
			}					
		}
		catch(Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * precondition: defIns is a method call
	 * 
	 * @param allLeafDepVars
	 * @param defIns
	 * @param inputVarArray
	 */
	private String exploreInterproceduralInstruction(Set<DepVariable> allLeafDepVars, BytecodeInstruction defIns,
			List<DepVariable>[] inputVarArray, String recommendedClass) {
		
		/**
		 * is the method static?
		 */
		int parameterStartIndex = 1;
		if(defIns.isCallToStaticMethod()) {
			parameterStartIndex = 0;
		}
		
		Map<String, Set<DepVariable>> relatedVariableMap = analyzeReturnValueFromMethod(defIns, recommendedClass);
		if(relatedVariableMap.isEmpty()) {
			return null;
		}
		
		String className = relatedVariableMap.keySet().iterator().next();
		Set<DepVariable> relatedVariables = relatedVariableMap.get(className);
		
		for(DepVariable var: relatedVariables) {
			for(DepVariable rootVar: var.getRootVars()) {
				if(rootVar.getInstruction().getClassName().
						equals(defIns.getCalledCFG().getClassName()) && 
						rootVar.getInstruction().getMethodName().equals(defIns.getCalledCFG().getMethodName())) {
					if(var.getType() == DepVariable.STATIC_FIELD) {
						allLeafDepVars.add(var);					
					}
					else {
						ConstructionPath path = rootVar.findPath(var);
						if(path != null) {
							DepVariable secVar = path.getPath().get(1);
							if(rootVar.getType() == DepVariable.PARAMETER) {
								int index = parameterStartIndex + rootVar.getParamOrder() - 1;
								List<DepVariable> params = inputVarArray[index];
								
								for(DepVariable param: params) {
									param.buildRelation(secVar, path.getPosition().get(0));
								}
							}
							else if(rootVar.getType() == DepVariable.THIS) {
								List<DepVariable> objectVars = inputVarArray[0];
								for(DepVariable objectVar: objectVars) {
									objectVar.buildRelation(secVar, path.getPosition().get(0));									
								}
							}
						}
					}
				}
			}
			
			allLeafDepVars.add(var);
		}
		
		return className;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private List<DepVariable>[] buildInputOutputForInstruction(BytecodeInstruction defInstruction, MethodNode node, 
			DepVariable outputVar, ActualControlFlowGraph cfg, Set<DepVariable> allDepVars, Set<BytecodeInstruction> visitedIns) {
		List<DepVariable>[] intputVarArray = new ArrayList[DepVariable.OPERAND_NUM_LIMIT];
		int operandNum = defInstruction.getOperandNum();
		for (int i = 0; i < operandNum; i++) {
			List<DepVariable> inputVars = new ArrayList<DepVariable>();
			
			Frame frame = defInstruction.getFrame();
			int index = frame.getStackSize() - operandNum + i;
			Value val = frame.getStack(index);
			
			SourceValue inputVal = (SourceValue)val;
			for(AbstractInsnNode newDefInsNode: inputVal.insns) {
				BytecodeInstruction newDefIns = convert2BytecodeInstruction(cfg, node, newDefInsNode);
				DepVariable inputVar = parseVariable(newDefIns);
				inputVar.buildRelation(outputVar, i);
				
				searchDependantVariables(val, cfg, allDepVars, visitedIns);
				
				inputVars.add(inputVar);
			}
			
			intputVarArray[i] = inputVars;
		}
		
		return intputVarArray;
	}

	public BytecodeInstruction convert2BytecodeInstruction(ActualControlFlowGraph cfg, MethodNode node,
			AbstractInsnNode ins) {
		AbstractInsnNode condDefinition = (AbstractInsnNode)ins;
		BytecodeInstruction defIns = cfg.getInstruction(node.instructions.indexOf(condDefinition));
		return defIns;
	}

	public MethodNode getMethodNode(InstrumentingClassLoader classLoader, String className, String methodName) {
		InputStream is = ResourceList.getInstance(classLoader).getClassAsStream(className);
		try {
			ClassReader reader = new ClassReader(is);
			ClassNode cn = new ClassNode();
			reader.accept(cn, ClassReader.SKIP_FRAMES);
			List<MethodNode> l = cn.methods;

			for (MethodNode n : l) {
				String methodSig = n.name + n.desc;
				if (methodSig.equals(methodName)) {
					return n;
				}
			}
			
			// Can't find the method in current class
			// Check its parent class
			try {
				Class<?> clazz = Class.forName(className);
				if (clazz.getSuperclass() != null) {
					Class<?> superClazz = clazz.getSuperclass();
					return getMethodNode(classLoader, superClazz.getName(), methodName);
//					System.currentTimeMillis();
				}
				System.currentTimeMillis();
			} catch (ClassNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		} catch (IOException e) {
			e.printStackTrace();
		}

		return null;
	}
}