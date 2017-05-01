package org.apache.sling.samples.test;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Service;

@Component
@Service
public class MyServiceImpl implements MyService {

  private boolean ready = true;
  
	@Override
	public String getString() {
		return "Uno";
	}
  public void setReady(boolean ready){
    this.ready = ready;
  }
  public boolean getReady(){
    return this.ready;
  }
}
