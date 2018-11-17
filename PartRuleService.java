package com.hpe.sta.ruleengine;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.apache.log4j.Logger;
import org.drools.template.ObjectDataCompiler;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.io.Resource;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.AgendaFilter;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.api.runtime.rule.Match;
import org.kie.internal.utils.KieHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hpe.sta.beans.CompanyGroupRuleCriteria;
import com.hpe.sta.beans.CompanyGroupingRule;
import com.hpe.sta.beans.Fact;
import com.hpe.sta.beans.PartyDimension;
import com.hpe.sta.config.AppConfig;
import com.hpe.sta.dao.UserDao;
import com.hpe.sta.drools.rulebuilder.RuleDescriptor;

@Service

public class PartRuleService {


	@Autowired
	private KieServices kieServices;

	@Autowired
	private UserDao userDao;

	private KieBase kieBase;

	private List<CompanyGroupRuleCriteria> ruleCriteria = new ArrayList<CompanyGroupRuleCriteria>();

	//private List<RuleDescriptor> ruleAttributes = new ArrayList<RuleDescriptor>();
	
	private static final Logger logger = Logger.getLogger(AppConfig.class);
	
	private final Map<String, String> fieldMap = new HashMap<String, String>(){
		{
			put("State/Province", "physicalProvince");
			put("Address Country", "partyCountry");
			put("City", "physicalCity");
			put("Party ID", "partyId");
			put("Party ID and Below","parentId");
			put("I", "==");
			put("E", "!=");
			put("Zip Code Single" , "physicalPostalCode");
			put("Company Group" , "companyGroup");
			put("World Region" , "worldRegion");
			put("Zip Code Range","physicalPostalCode");
			
		}
	};


	@PostConstruct
	public void buildRulecriteria() {
		ruleCriteria = userDao.retrieveRuleCriteria();
		if(ruleCriteria!= null &&  ruleCriteria.size() > 0)
			buildRules(ruleCriteria);
	}

	public void buildRules(List<CompanyGroupRuleCriteria> ruleCriteria2) {
		List<RuleDescriptor> ruleAttributes = new ArrayList<RuleDescriptor>();
		for (CompanyGroupRuleCriteria criteria : ruleCriteria2) {
			String condition = "";
			if(null != criteria.getCompanyCriteriaValueOneText() && null != criteria.getCompanyCriteriaValueTwoText()
					&& !criteria.getCompanyCriteriaValueOneText().isEmpty() && !criteria.getCompanyCriteriaValueTwoText().isEmpty()
					){
				condition = buildIfConditionforZipCode(criteria);
			} else{
				condition = buildIfCondition(criteria);
			}
			String ifCondition = "$fact : Fact(" + condition + ")";
			long criteriaId = criteria.getCompanyGroupRuleCriteriaId();
			long ruleId = criteria.getCompanyGroupRuleId();
			String thenCondition = "satisfiedRules";
			RuleDescriptor descriptor = new RuleDescriptor(ifCondition,thenCondition, ruleId, criteriaId);
			ruleAttributes.add(descriptor);
		}
		ObjectDataCompiler compiler = new ObjectDataCompiler();
		InputStream is = this.getClass().getClassLoader().getResourceAsStream("template.drl");
		String generatedDRL = compiler.compile(ruleAttributes, is);
		System.out.println(generatedDRL);
		buildRulesintoEngine(generatedDRL);
	}

	private void buildRulesintoEngine(String generatedDRL) {
		KieHelper kieHelper = new KieHelper();
		byte[] b1 = generatedDRL.getBytes();
		Resource resource1 = kieServices.getResources().newByteArrayResource(b1);
		kieHelper.addResource(resource1, ResourceType.DRL);
		long time = System.currentTimeMillis();
		kieBase = kieHelper.build();
		logger.info("Time taken to build all the rules from UI "+(System.currentTimeMillis() - time));
	}

	private String buildIfCondition(CompanyGroupRuleCriteria criteria) {

		String field = null;String operator = null;
		operator = fieldMap.get(criteria.getCompanyExcludeIncludeOperator());
		field = fieldMap.get(criteria.getCompanyGroupRuleCriteriaTypeName());
		String value = "\"" + criteria.getCompanyCriteriaValueOneText() + "\"";
		String fieldOf = "";
		Object object = new PartyDimension();
		Field[] objectClass = object.getClass().getDeclaredFields();
	    for (Field field2 : objectClass) {
	        if (field2.getName().equals(field)) {
	        	fieldOf = "party.";
	        	break;
	        }
	    }
	    if(fieldOf.isEmpty())
	    	fieldOf = "partyHier."; 
	    
	    String condition = "";
	   
	    if(null != criteria.getCompanyCriteriaValueOneText()){
	    	String [] values = criteria.getCompanyCriteriaValueOneText().split(",");
	    	 for (int i = 0; i < values.length; i++) {
	    		 if(!(field.equalsIgnoreCase("parentId"))){
	    			 if(condition.isEmpty())
		    			 condition = fieldOf + field + operator + "\"" +values[i] + "\"";
		    		 else
		    			 condition = condition + " || " + (fieldOf + field + operator + "\"" +values[i] + "\"");
	    		 }else if(field.equalsIgnoreCase("parentId")){
	    			 if(condition.isEmpty())
	    			     condition = "parentId.contains(" + "\"" + values[i] + "\")" ;
		    		 else
		    			 condition = condition + " || " + "parentId.contains(" + "\"" + values[i] + "\")";
	    		 }
	    		 
	 		}
	    }
	    
		return condition;
	}
	
	private String buildIfConditionforZipCode(CompanyGroupRuleCriteria criteria) {
		String value1 = "\"" + criteria.getCompanyCriteriaValueOneText() + "\"";
		String value2 = "\"" + criteria.getCompanyCriteriaValueTwoText() + "\"";
		String cond = value1  + ".compareTo(party.physicalPostalCode)  <= 0 && " + value2 + ".compareTo(party.physicalPostalCode) >= 0 ";
		return cond;
	}

	public List<Long> fireRules(Fact fact, ArrayList<String> value) {
		List<Long> satisfiedRules = new ArrayList<Long>();
		PartyDimension party = fact.getParty();
		fact.setParty(party);
		fact.setParentId(value);
		KieSession kieSession = kieBase.newKieSession();
		kieSession.setGlobal("satisfiedRules", new ArrayList<Long>());
		FactHandle handle = kieSession.insert(fact);
		int numberOfRulesFired = kieSession.fireAllRules();
		System.out.println("rules fires" + numberOfRulesFired);
		satisfiedRules = (List<Long>) kieSession.getGlobal("satisfiedRules");
		kieSession.retract(handle);
		kieSession.dispose();
		System.out.println("fact output: " + Arrays.toString(satisfiedRules.toArray()));
		return satisfiedRules;
	}

	
	
	public List<Long> simulateRules(Fact fact, List<Long> rulesTobeSimulated, ArrayList<String> value) {
		
		List<Long> satisfiedRules = new ArrayList<Long>();
		KieSession kieSession = kieBase.newKieSession();
		final List<String> simulatedRule = new ArrayList<String>();
		for (int i = 0; i < rulesTobeSimulated.size(); i++) {
			simulatedRule.add(String.valueOf(rulesTobeSimulated.get(i)));
		}
		kieSession.setGlobal("satisfiedRules", new ArrayList());
		fact.setParentId(value);
		if("49765467".equalsIgnoreCase(fact.getParty().getPartyId())){
			System.out.println("gdsgs");
		}
		FactHandle handle = kieSession.insert(fact);
		int numberOfRulesFired = kieSession.fireAllRules(new AgendaFilter() {
			@Override
			public boolean accept(Match match) {
				if (simulatedRule.contains(match.getRule().getName())) {
					return true;
				}
				return false;
			}
		});
		logger.info("Number of rules fired" +numberOfRulesFired);
		satisfiedRules = (List) kieSession.getGlobal("satisfiedRules");
		kieSession.retract(handle);
		return satisfiedRules;
	}
	
	
	public void buildRulesfromUI(CompanyGroupingRule ruleCreationObj) {

		
		List<CompanyGroupRuleCriteria> ruleCriteria2 = ruleCreationObj.getCompanyGroupRuleCriteria();
//		for (int i = 0; i < lengthRuleCriteria; i++) {
//			org.json.JSONObject jsonRuleCriteriaObj = jsonArrayRuleCriteria.getJSONObject(i);
//			String strOperator = String.valueOf(jsonRuleCriteriaObj.getBoolean("operator"));
//			if (strOperator.equals("true")) {
//				strOperator = "Exclude";
//			} else {
//				strOperator = "Include";
//			}
//			String field = jsonRuleCriteriaObj.getString("field");
//			if(field.equals("PartyIdandbelow")){
//				field = "PartyID and Below";
//			} else if(field.equals("PartyID")){
//				field = "Party Id";
//			}  else {
//				field = jsonRuleCriteriaObj.getString("field");
//			}
//			CompanyGroupRuleCriteria criteria = new CompanyGroupRuleCriteria();
//			criteria.setCompanyGroupRuleId(ruleId);
//			criteria.setCompanyGroupRuleCriteriaTypeCode(field);
//			criteria.setCompanyExcludeIncludeOperator(strOperator);
//			criteria.setCompanyCriteriaValueOneText(jsonRuleCriteriaObj.getString("value"));
//			criteria.setCompanyCriteriaValueTwoText(jsonRuleCriteriaObj.getString("valueOne"));
//			criteria.setCompanyGroupRuleCriteriaId(jsonRuleCriteriaObj.getInt("criteriaId"));
//			ruleCriteria2.add(criteria);
//		}
		buildRules(ruleCriteria2);
	}

}
