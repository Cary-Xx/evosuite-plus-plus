package regression.visibility.test;

import java.lang.reflect.Method;

import org.evosuite.Properties;
import org.evosuite.Properties.StatisticsBackend;
import org.evosuite.utils.MethodUtil;
import org.junit.Test;

import com.test.TestUtility;

import regression.visibility.example.VsibilityExample;

public class VisibilityTest extends TestUtility{
	@Test
	public void testVisibility() {
		Class<?> clazz = VsibilityExample.class;
		String methodName = "example";
		int parameterNum = 1;
		
		String targetClass = clazz.getCanonicalName();
//		Method method = clazz.getMethods()[0];
		Method method = TestUtility.getTargetMethod(methodName, clazz, parameterNum);

		String targetMethod = method.getName() + MethodUtil.getSignature(method);
		String cp = "target/classes";

		// Properties.LOCAL_SEARCH_RATE = 1;
//		Properties.DEBUG = true;
//		Properties.PORT = 8000;
		Properties.CLIENT_ON_THREAD = true;
		Properties.STATISTICS_BACKEND = StatisticsBackend.DEBUG;

//		Properties.TIMEOUT = 10000000;
//		Properties.TIMELINE_INTERVAL = 3000;
		
		String fitnessApproach = "fbranch";
		
		int timeBudget = 100;
		TestUtility.evosuite(targetClass, targetMethod, cp, timeBudget, true, fitnessApproach);
	}
}
