package ex15.pyrmont.digestertest;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;

import org.apache.commons.digester.Digester;

public class Test02 {

	public static void main(String[] args) {
		String path = System.getProperty("user.dir") + File.separator + "etc";
		File file = new File(path, "employee2.xml");
		Digester digester = new Digester();
		//注意下面两句的区别。
		//digester.addObjectCreate("employee", "ex15.pyrmont.digestertest.Employee","className");
		digester.addObjectCreate("employee", "ex15.pyrmont.digestertest.Employee","className");
		digester.addSetProperties("employee");


		digester.addObjectCreate("employee/office", "ex15.pyrmont.digestertest.Office");
		digester.addSetProperties("employee/office");
		digester.addSetNext("employee/office", "addOffice","ex15.pyrmont.digestertest.Office");

		digester.addObjectCreate("employee/office/address",
				"ex15.pyrmont.digestertest.Address");
		digester.addSetProperties("employee/office/address");
		digester.addSetNext("employee/office/address", "setAddress","ex15.pyrmont.digestertest.Address");
		try {
			//这里跟随上面一起变。
			//Employee employee = (Employee) digester.parse(file);
			Employee2 employee = (Employee2) digester.parse(file);
			ArrayList offices = employee.getOffices();
			Iterator iterator = offices.iterator();
			System.out.println("-------------------------------------------------");
			while (iterator.hasNext()) {
				Office office = (Office) iterator.next();
				Address address = office.getAddress();
				System.out.println(office.getDescription());
				System.out.println("Address : " +
						address.getStreetNumber() + " " + address.getStreetName());
				System.out.println("--------------------------------");
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
