package nl.vu.jena.sparql.engine.iterator;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.sparql.core.Var;
import com.hp.hpl.jena.sparql.engine.ExecutionContext;
import com.hp.hpl.jena.sparql.engine.QueryIterator;
import com.hp.hpl.jena.sparql.engine.binding.Binding;
import com.hp.hpl.jena.sparql.engine.binding.BindingFactory;
import com.hp.hpl.jena.sparql.engine.binding.BindingMap;
import com.hp.hpl.jena.sparql.engine.iterator.QueryIterNullIterator;
import com.hp.hpl.jena.sparql.engine.iterator.QueryIterPlainWrapper;
import com.hp.hpl.jena.sparql.engine.main.iterator.QueryIterJoinBase;
import com.hp.hpl.jena.sparql.expr.ExprList;

/**
 * Used when we know there are no intersecting columns between left and right
 *
 */
public class QueryIterCartesianProduct extends QueryIterJoinBase {
	
	public QueryIterCartesianProduct(QueryIterator left, QueryIterator right, ExecutionContext execCxt) {
		super(left, right, null, execCxt);
	}

	@Override
	protected QueryIterator joinWorker() {
		
		QueryIterator rightIterator = super.tableRight.iterator(null);
		
		List<Binding> out = new ArrayList<Binding>() ;
		Binding leftBinding = getLeft().nextBinding();
		
		while (rightIterator.hasNext()){
			Binding right = rightIterator.next();		
			BindingMap newBinding = BindingFactory.create(leftBinding) ;
	        for (Iterator<Var> vIter = right.vars() ; vIter.hasNext() ;)
	        {
	            Var v = vIter.next();
	            Node n = right.get(v) ;
	            newBinding.add(v, n) ;
	        }
	        
	        out.add(newBinding);
		}
		
		if (out.size() == 0){
            return new QueryIterNullIterator(getExecContext()) ;
		}
		
        return new QueryIterPlainWrapper(out.iterator(), getExecContext());
	}

	

}
